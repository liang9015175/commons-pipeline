/*
 * Copyright 2004 The Apache Software Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.commons.pipeline.driver;

import java.lang.Thread.UncaughtExceptionHandler;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.commons.pipeline.Feeder;
import org.apache.commons.pipeline.StageDriver;
import org.apache.commons.pipeline.Stage;
import org.apache.commons.pipeline.StageContext;
import org.apache.commons.pipeline.StageException;
import org.apache.commons.pipeline.driver.AbstractStageDriver;
import org.apache.commons.pipeline.driver.FaultTolerance;

import static org.apache.commons.pipeline.StageDriver.State.*;
import static org.apache.commons.pipeline.driver.FaultTolerance.*;

/**
 * This {@link StageDriver} implementation uses a pool of threads
 * to process objects from an input queue.
 */
public class ThreadPoolStageDriver extends AbstractStageDriver {
    private final Log log = LogFactory.getLog(ThreadPoolStageDriver.class);
    
    //wait timeout to ensure deadlock cannot occur on thread termination
    private long timeout;
    
    //flag describing whether or not the driver is fault tolerant
    private FaultTolerance faultTolerance = FaultTolerance.NONE;
    
    //signal telling threads to start polling queue
    final private CountDownLatch startSignal;
    
    //signal threads use to tell driver they have finished
    final private CountDownLatch doneSignal;
    
    // number of threads polling queue
    private int numThreads = 1;
    
    //queue to hold data to be processed
    private BlockingQueue queue;
    
    //current state of thread processing
    private volatile State currentState = State.STOPPED;
    
    //feeder used to feed data to this stage's queue
    private final Feeder feeder = new Feeder() {
        public void feed(Object obj) {
            if (log.isDebugEnabled()) log.debug(obj + " is being fed to stage " + stage
                    + " (" + ThreadPoolStageDriver.this.queue.remainingCapacity() + " available slots in queue)");
            try {
                ThreadPoolStageDriver.this.queue.put(obj);
            } catch (InterruptedException e) {
                throw new IllegalStateException("Unexpected interrupt while waiting for space to become available for object "
                        + obj + " in queue for stage " + stage, e);
            }
            synchronized(ThreadPoolStageDriver.this) {
                ThreadPoolStageDriver.this.notifyAll();
            }
        }
    };
    
    /**
     * Creates a new ThreadPoolStageDriver.
     *
     * @param stage The stage that the driver will run
     * @param context the context in which to run the stage
     * @param queue The object queue to use for storing objects prior to processing. The
     * default is {@link LinkedBlockingQueue}
     * @param timeout The amount of time, in milliseconds, that the worker thread
     * will wait before checking the processing state if no objects are available
     * in the thread's queue.
     * @param faultTolerance Flag determining the behavior of the driver when
     * an error is encountered in execution of {@link Stage#process(Object)}.
     * If this is set to false, any exception thrown during {@link Stage#process(Object)}
     * will cause the worker thread to halt without executing {@link Stage#postprocess()}
     * ({@link Stage#release()} will be called.)
     * @param numThreads Number of threads that will be simultaneously reading from queue
     */
    public ThreadPoolStageDriver(Stage stage, StageContext context,
            BlockingQueue queue,
            long timeout,
            FaultTolerance faultTolerance,
            int numThreads) {
        super(stage, context);
        this.numThreads = numThreads;
        
        this.startSignal = new CountDownLatch(1);
        this.doneSignal = new CountDownLatch(this.numThreads);
        
        this.queue = queue;
        this.timeout = timeout;
        this.faultTolerance = faultTolerance;
    }
    
    /**
     * Return the Feeder used to feed data to the queue of objects to be processed.
     * @return The feeder for objects processed by this driver's stage.
     */
    public Feeder getFeeder() {
        return this.feeder;
    }
    
    /**
     * Start the processing of the stage. Creates threads to poll items
     * from queue.
     * @throws org.apache.commons.pipeline.StageException Thrown if the driver is in an illegal state during startup
     */
    public synchronized void start() throws StageException {
        if (this.currentState == STOPPED) {
            setState(STARTED);
            
            if (log.isDebugEnabled()) log.debug("Preprocessing stage " + stage + "...");
            stage.preprocess();
            if (log.isDebugEnabled()) log.debug("Preprocessing for stage " + stage + " complete.");
            
            log.debug("Starting worker threads for stage " + stage + ".");
            
            for (int i=0;i<this.numThreads;i++) {
                new LatchWorkerThread(i).start();
            }
            
            // let threads know they can start
            testAndSetState(STARTED, RUNNING);
            startSignal.countDown();
            
            log.debug("Worker threads for stage " + stage + " started.");
            
            //wait to ensure that the stage starts up correctly
            try {
                while ( !(this.currentState == RUNNING || this.currentState == ERROR) ) this.wait();
            } catch (InterruptedException e) {
                throw new StageException(this.getStage(), "Worker thread unexpectedly interrupted while waiting for thread startup.", e);
            }
        } else {
            throw new IllegalStateException("Attempt to start driver in state " + this.currentState);
        }
    }
    
    /**
     * Causes processing to shut down gracefully. Waits until all worker threads
     * have completed.
     * @throws org.apache.commons.pipeline.StageException Thrown if the driver is in an illegal state for shutdown.
     */
    public synchronized void finish() throws StageException {
        if (currentState == STOPPED) {
            throw new IllegalStateException("The driver is not currently running.");
        }
        
        try {
            while ( !(this.currentState == RUNNING || this.currentState == ERROR) ) this.wait();
            
            //ask the worker threads to shut down
            testAndSetState(RUNNING, STOP_REQUESTED);
            
            if (log.isDebugEnabled()) log.debug("Waiting for worker threads to stop for stage " + stage + ".");
            doneSignal.await();
            
            if (log.isDebugEnabled()) log.debug("Postprocessing stage " + stage + "...");
            ThreadPoolStageDriver.this.stage.postprocess();
            if (log.isDebugEnabled()) log.debug("Postprocessing for stage " + stage + " complete.");
            
            //do not transition into finished state if an error has occurred
            testAndSetState(STOP_REQUESTED, FINISHED);
            
            while ( !(this.currentState == FINISHED || this.currentState == ERROR) ) this.wait();
            
            log.debug("Worker threads for stage " + stage + " halted");
        } catch (StageException e) {
            log.error("An error occurred during postprocess for stage " + stage , e);
            recordFatalError(e);
            setState(ERROR);
        } catch (InterruptedException e) {
            throw new StageException(this.getStage(), "StageDriver unexpectedly interrupted while waiting for shutdown of worker threads.", e);
        } finally {
            if (log.isDebugEnabled()) log.debug("Releasing resources for stage " + stage + "...");
            stage.release();
            if (log.isDebugEnabled()) log.debug("Stage " + stage + " released.");
        }
        
        setState(STOPPED);
    }
    
    /**
     * Return the current state of stage processing.
     * @return the current state of processing
     */
    public StageDriver.State getState() {
        return this.currentState;
    }
    
    /**
     * Atomically tests to determine whether or not the driver is in the one of
     * the specified states.
     */
    private synchronized boolean isInState(State... states) {
        for (State state : states) if (state == currentState) return true;
        return false;
    }
    
    /**
     * Set the current state of stage processing and notify any listeners
     * that may be waiting on a state change.
     */
    private synchronized void setState(State nextState) {
        if (log.isDebugEnabled()) log.debug("State change for " + stage + ": " + this.currentState + " -> " + nextState);
        this.currentState = nextState;
        this.notifyAll();
    }
    
    /**
     * This method performs an atomic conditional state transition change
     * to the value specified by the nextState parameter if and only if the
     * current state is equal to the test state.
     */
    private synchronized boolean testAndSetState(State testState, State nextState) {
        if (currentState == testState) {
            setState(nextState);
            return true;
        } else {
            return false;
        }
    }
    
    /**
     * Sets the failure tolerance flag for the worker thread. If faultTolerance
     * is set to CHECKED, {@link StageException StageException}s thrown by
     * the {@link Stage#process(Object)} method will not interrupt queue
     * processing, but will simply be logged with a severity of ERROR.
     * If faultTolerance is set to ALL, runtime exceptions will also be
     * logged and otherwise ignored.
     * @param faultTolerance the flag value
     */
    public final void setFaultTolerance(String faultTolerance) {
        this.faultTolerance = FaultTolerance.valueOf(faultTolerance);
    }
    
    /**
     * Sets the failure tolerance flag for the worker thread. If faultTolerance
     * is set to CHECKED, {@link StageException StageException}s thrown by
     * the {@link Stage#process(Object)} method will not interrupt queue
     * processing, but will simply be logged with a severity of ERROR.
     * If faultTolerance is set to ALL, runtime exceptions will also be
     * logged and otherwise ignored.
     * @param faultTolerance the flag value
     */
    public final void setFaultTolerance(FaultTolerance faultTolerance) {
        this.faultTolerance = faultTolerance;
    }
    
    /**
     * Getter for property faultTolerant.
     * @return Value of property faultTolerant.
     */
    public FaultTolerance getFaultTolerance() {
        return this.faultTolerance;
    }
    
    /*********************************
     * WORKER THREAD IMPLEMENTATIONS *
     *********************************/
    private UncaughtExceptionHandler workerThreadExceptionHandler = new UncaughtExceptionHandler() {
        public void uncaughtException(Thread t, Throwable e) {
            setState(ERROR);
            recordFatalError(e);
            log.error("Uncaught exception in stage " + stage, e);
        }
    };
    
    /**
     * This worker thread removes and processes data objects from the incoming
     * synchronize queue. It calls the Stage's process() method to process data
     * from the queue. This loop runs until State has changed to
     * STOP_REQUESTED. To break the loop the calling code must run the writer's
     * finish() method to set the running property to false.
     *
     * @throws StageException if an error is encountered during data processing
     * and faultTolerant is set to false.
     */
    private class LatchWorkerThread extends Thread {
        final int threadID;
        
        LatchWorkerThread(int threadID) {
            this.setUncaughtExceptionHandler(workerThreadExceptionHandler);
            this.threadID = threadID;
        }        
        
        public final void run() {
            try {
                ThreadPoolStageDriver.this.startSignal.await();
                //do not transition into running state if an error has occurred or a stop requested
                running: while (currentState != ERROR) {
                    try {
                        Object obj = queue.poll(timeout, TimeUnit.MILLISECONDS);
                        if (obj == null) {
                            if (currentState == STOP_REQUESTED) break running;
                        } else {
                            try {
                                stage.process(obj);
                            } catch (StageException e) {
                                recordProcessingException(obj, e);
                                if (faultTolerance == NONE) throw e;
                            } catch (RuntimeException e) {
                                recordProcessingException(obj, e);
                                if (faultTolerance == CHECKED || faultTolerance == NONE) throw e;
                            }
                        }
                    } catch (InterruptedException e) {
                        throw new RuntimeException("Worker thread " + this.threadID + " unexpectedly interrupted while waiting on data for stage " + stage, e);
                    }
                }
                if (log.isDebugEnabled()) log.debug("Stage " + stage + " (threadID: " + this.threadID + ") exited running state.");
                
            } catch (StageException e) {
                log.error("An error occurred in the stage " + stage + " (threadID: " + this.threadID + ")", e);
                recordFatalError(e);
                setState(ERROR);
            } catch (InterruptedException e) {
                log.error("Stage " + stage + " (threadID: " + threadID + ") interrupted while waiting for barrier",e);
                recordFatalError(e);
                setState(ERROR);
            } finally {
                doneSignal.countDown();
                synchronized (ThreadPoolStageDriver.this) {
                    ThreadPoolStageDriver.this.notifyAll();
                }
            }
        }
    }
    
    /**
     * Get the size of the queue used by this StageDriver.
     * @return the queue capacity
     */
    public int getQueueSize() {
        return this.queue.size() + this.queue.remainingCapacity();
    }
    
    /**
     * Get the timeout value (in milliseconds) used by this StageDriver on
     * thread termination.
     * @return the timeout setting in milliseconds
     */
    public long getTimeout() {
        return this.timeout;
    }
    
    /**
     * Returns the number of threads allocated to the thread pool.
     */
    public int getNumThreads() {
        return numThreads;
    }
}
