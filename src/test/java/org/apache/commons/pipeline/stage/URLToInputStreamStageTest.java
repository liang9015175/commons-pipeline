/*
 * Copyright 2005 The Apache Software Foundation
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

package org.apache.commons.pipeline.stage;

import java.io.IOException;
import java.io.InputStream;
import junit.framework.*;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.pipeline.Pipeline;
import org.apache.commons.pipeline.Stage;


/**
 * Test cases for URLToInputStreamStaticStage.
 */
public class URLToInputStreamStageTest extends AbstractStageTest {
    
    URL url;
    
    public URLToInputStreamStageTest(String testName) {
        super(testName);
    }
    
    protected void setUp() throws Exception {
        super.setUp();
        this.url = this.getClass().getClassLoader().getResource("url-input-to-stream-test.txt");
        assertNotNull(url);
    }
    
    public static Test suite() {
        TestSuite suite = new TestSuite(URLToInputStreamStageTest.class);
        
        return suite;
    }
    
    /**
     * Test of process method, of class org.apache.commons.pipeline.stage.URLToInputStreamStage.
     */
    public void testProcess() throws Exception {
        URLToInputStreamStage stage = new URLToInputStreamStage();
        this.init(stage);
        
        stage.process(url);
        
        assertEquals(1, testFeeder.receivedValues.size());
        
        InputStream in = (InputStream) testFeeder.receivedValues.get(0);
        try {
            assertNotNull(in);
        byte[] buffer = new byte[128];
            int bytes = in.read(buffer);
        } finally {
            in.close();
        }
    }
    
    /**
     * Test of postprocess method, of class org.apache.commons.pipeline.stage.URLToInputStreamStage.
     */
    public void testPostprocess() throws Exception {
        URLToInputStreamStage stage = new URLToInputStreamStage();
        this.init(stage);
        
        stage.process(url);
        stage.release();
        
        assertEquals(1, testFeeder.receivedValues.size());

        InputStream in = (InputStream) testFeeder.receivedValues.get(0);
        try {
            byte[] buffer = new byte[128];
            int bytes = in.read(buffer);
            fail("input stream should have been closed, so reading should throw an exception.");
        } catch (IOException expected){
            // do nothing
        }
    }
}