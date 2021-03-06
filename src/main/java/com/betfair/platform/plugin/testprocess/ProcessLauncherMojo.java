/*
Copyright 2013, The Sporting Exchange Limited

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
 */
package com.betfair.platform.plugin.testprocess;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;

/**
 * @goal test
 * @phase test
 */
public class ProcessLauncherMojo extends AbstractMojo {
   private static final int processTerminationCharacter = 3; // CTRL-C
   private static final String CONTAINER = "CONTAINER";
   private static final String TESTER = "TESTER";

	/**
     * Max time (in ms) to allow all containers to start. If all containers are not started in this time then this build will fail.
	 * @parameter
	 */
	private String containersStartupTimeout;

	/**
     * Max time (in ms) to allow all test processes to take. If all tests are not completed in this time then this build will fail.
	 * @parameter
	 */
	private String testsCompletionTimeout;

   /**
    * Whether to abort early or try to run further test processes if one fails.
    * @parameter default-value='true'
    */
   private boolean failFast = true;
    /**
     * The containers to run which will be tested.
     * @parameter
     */
    private ContainerProcess[] containerProcesses;

   /**
    * The test processes which will be run against the containers.
    * @parameter
    */
   private TestProcess[] testProcesses;

    /**
     * Environment variables which should never be dumped out to stdout when running
     * @parameter default-value=''
     */
    private String privateEnvironmentVariables;


    private volatile boolean finishImmediately;
    private volatile boolean failed;
    private Map<String, CountDownLatch> testProcessFinishLatches;
    private Map<ContainerProcess, CountDownLatch> containerProcessStartLatches;
    private Map<String, ContainerProcess> containerProcessesById;
    private List<String> failedProcesses;



    public void execute() throws MojoExecutionException, MojoFailureException {

		List<Process> _containerSystemProcesses;
		Process _testerProcess = null;
		ReaderRunnable testReaderRunnable;
		ReaderRunnable containerReaderRunnable;
        String testerId;
		
		validate(); 

		int terminationChar = processTerminationCharacter;

        finishImmediately = false;
        failed = false;
        containerProcessStartLatches = new HashMap<ContainerProcess, CountDownLatch>();
        testProcessFinishLatches = new HashMap<String, CountDownLatch>();
        containerProcessesById = new HashMap<String, ContainerProcess>();
        failedProcesses = new ArrayList<String>();

        _containerSystemProcesses = new ArrayList<Process>();

        for (ContainerProcess cp : containerProcesses) {
            CountDownLatch latch = new CountDownLatch(1);
            String containerId = CONTAINER+"["+cp.getId()+"]";
            containerProcessesById.put(containerId, cp);
            ProcessBuilder containerProcessBuilder = cp.createProcessBuilder();

            dumpEnvironment(containerProcessBuilder.environment(), cp.getId());
            try {
                containerProcessStartLatches.put(cp, latch);

                getLog().info("Starting '" + cp.getCommand() + "' in directory '" + cp.getWorkingDir() +"'");
                Process _containerProcess = containerProcessBuilder.start();
                _containerSystemProcesses.add(_containerProcess);
                containerReaderRunnable = new ReaderRunnable(containerId, _containerProcess.getInputStream(), getLog());
                if (cp.getStartWatchString() != null || !"".equals(cp.getStartWatchString())) {
                    containerReaderRunnable.setNotifyText(cp.getStartWatchString());
                    containerReaderRunnable.setFailureNotifyText(cp.getFailureWatchString());
                }

                containerReaderRunnable.setListener(this);
                Thread containerReaderRunnableThread = new Thread(containerReaderRunnable);
                containerReaderRunnableThread.start();

                getLog().info("Started '" + cp.getCommand() );

            } catch (IOException e) {
                throw new MojoExecutionException("Unable to start " + CONTAINER +" process " + e );
            }
        }



        long containerTimeout = -1;
        try {
            containerTimeout = Long.parseLong(containersStartupTimeout);
        }
        catch (NumberFormatException nfe) {
            // ignore
        }

        failed = false;
        finishImmediately = false;

        // wait for the container to start
        String currentWaitString = null;
        try {
            boolean anyFailed = false;
            if (containerTimeout == -1) {
                for (ContainerProcess container : containerProcessStartLatches.keySet()) {
                    CountDownLatch latch = containerProcessStartLatches.get(container);
                    if (container.getStartWatchString() != null && !"".equals(container.getStartWatchString())) {
                        currentWaitString = container.getStartWatchString();
                        latch.await();
                    }
                }
            }
            else {
                long latestEndTime = System.currentTimeMillis() + containerTimeout;
                for (ContainerProcess container : containerProcessStartLatches.keySet()) {
                    CountDownLatch latch = containerProcessStartLatches.get(container);
                    if (container.getStartWatchString() != null && !"".equals(container.getStartWatchString())) {
                        long maxWaitTime = latestEndTime - System.currentTimeMillis();
                        boolean completed = latch.await(maxWaitTime, TimeUnit.MILLISECONDS);
                        if (!completed) {
                            failedProcesses.add(CONTAINER+"["+ container.getId()+"]");
                            anyFailed = true;
                            break;
                        }
                    }
                }
            }
            if (anyFailed) {
                failed = true;
                finishImmediately = true;
            }
        } catch (InterruptedException ie) {
            getLog().warn("Interrupted waiting for notify text of '" + currentWaitString + "' to arrive");
        }

        if (!failed) {
            long uberTimeout = -1;
            if (testsCompletionTimeout != null && !"".equals(testsCompletionTimeout)) {
                uberTimeout = Long.parseLong(testsCompletionTimeout);
            }
            long targetEndTime = uberTimeout == -1 ? -1 : System.currentTimeMillis() + uberTimeout;


            for (TestProcess tp : testProcesses) {
                // run out of time
                if (uberTimeout == -1 && ((System.currentTimeMillis() - targetEndTime) <= 0)) {
                    finishImmediately = true;
                }
                // exit the loop if we have to (at the top so we can die after container failure)
                if (finishImmediately) {
                    break;
                }

                if (tp.getStartupDelay() != null && !"".equals(tp.getStartupDelay())) {
                    try {
                        long delayMs = Long.valueOf(tp.getStartupDelay());
                        Thread.sleep(delayMs);

                    } catch (InterruptedException ie) {
                        getLog().warn("Startup delay interrupted - you may get some timing issues");

                    } catch (NumberFormatException nfe) {
                        getLog().error("Invalid startup delay");
                        throw new MojoExecutionException("Invalid startup delay.  Expected a number, not '" + tp.getStartupDelay() + "'");
                    }
                }

                CountDownLatch latch = new CountDownLatch(1);
                testerId = TESTER+"["+tp.getId()+"]";
                try {
                    testProcessFinishLatches.put(testerId, latch);

                    ProcessBuilder testProcessBuilder = tp.createProcessBuilder();
                    getLog().info("Starting '" + tp.getCommand() + "' in directory '" + tp.getWorkingDir() + "'");
                    _testerProcess = testProcessBuilder.start();
                    testReaderRunnable = new ReaderRunnable(testerId, _testerProcess.getInputStream(), getLog());
                    testReaderRunnable.setFailureNotifyText(tp.getFailureWatchString());
                    testReaderRunnable.setNotifyText(tp.getWatchString());
                    testReaderRunnable.setListener(this);
                    Thread testReaderRunnableThread = new Thread(testReaderRunnable);
                    testReaderRunnableThread.start();

                    // make sure we catch the process completing normally
                    final Process p = _testerProcess;
                    final CountDownLatch l = latch;
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                p.waitFor();
                            } catch (InterruptedException e) {
                                // don't care
                            }
                            l.countDown();
                        }
                    });

                    getLog().info("Started '" + tp.getCommand() +"'");

                } catch (IOException e) {
                    getLog().info("Killing "+ CONTAINER + " process due to IO exception in, possibly in TEST PROCESS" + e );
                    throw new MojoExecutionException("Unable to start " + testerId + " process. " + e);
                }

                getLog().info("Waiting for " + testerId+" process to complete." );
                boolean timedOut;
                try {
                    long individualTimeout = tp.getCompletionTimeout() != null ? Long.parseLong(tp.getCompletionTimeout()) : -1;
                    long overallTimeout = uberTimeout != -1 ? targetEndTime - System.currentTimeMillis() : -1;
                    long timeout = Math.max(individualTimeout, overallTimeout);
                    if (timeout == -1) {
                        // not allowed
                        getLog().error("No timeout available, treating it as immediate timeout");
                        timedOut = true;
                    }
                    else {
                        timedOut = !latch.await(timeout, TimeUnit.MILLISECONDS);
                    }
                } catch (InterruptedException e) {
                    // ignore, will assume all not ok
                    timedOut = true;
                }

                boolean exitedSuccessfully;
                try {
                    exitedSuccessfully = _testerProcess.exitValue() == 0;
                }
                catch (IllegalThreadStateException itse) {
                    // process hasn't exited, so assume it was successful, unless we timed out (if it did fail and we
                    // noticed via the fail string, then "failed" will be false) - this is because processes don't seem to
                    // end nicely
                    exitedSuccessfully = true;
                }
                // timeout or failure code from the process
                if (timedOut || !exitedSuccessfully) {
                    failed = true;
                    failedProcesses.add(testerId);
                }
                if (failed) {
                    if (failFast) {
                        finishImmediately = true;
                    }
                }

                // now clean up
                getLog().warn(testerId+" process: exitedSuccessfully="+exitedSuccessfully);
                getLog().warn(testerId+" process: timedOut          ="+timedOut);
                getLog().warn(testerId+" process: failed            ="+failed);

                getLog().warn("Terminating " + testerId + " process cleanly @ "+new Date());
                try {
                    _testerProcess.getOutputStream().write(terminationChar);
                    _testerProcess.getOutputStream().flush();
                } catch (IOException e) {
                    // silent
                }
                _testerProcess.destroy();
                _testerProcess = null; // so noone else tries to cleanup later
            }
        }

        for (Process _containerProcess : _containerSystemProcesses) {
            getLog().warn("Destroying " + CONTAINER + " process @ "+new Date());
            try {
               _containerProcess.getOutputStream().write(terminationChar);
               _containerProcess.getOutputStream().flush();
            } catch (IOException e) {
               // silent
            }
            _containerProcess.destroy();
        }
        if (_testerProcess != null) {
            getLog().warn("Destroying " + TESTER + " process @ "+new Date());
            try {
               _testerProcess.getOutputStream().write(terminationChar);
               _testerProcess.getOutputStream().flush();
            } catch (IOException e) {
               // silent
            }
            _testerProcess.destroy();
        }

        if (failed) {
            String failedProcessesText = Arrays.toString(failedProcesses.toArray());
            throw new MojoFailureException("Failure text arrived on the following processes: " + failedProcessesText);
        }
    }

    public void notifyTextArrived(String id) {
        getLog().warn("Notify text arrived for "+id+ " @ "+new Date());
        if (id.startsWith(CONTAINER)) {
            ContainerProcess cp = containerProcessesById.get(id);
            containerProcessStartLatches.get(cp).countDown();
        }
        else if (id.startsWith(TESTER)) {
            testProcessFinishLatches.get(id).countDown();
        }
    }

    public void failureNotifyTextArrived(String id) {
        getLog().warn("Failure text arrived for "+id+ " @ "+new Date());
        failedProcesses.add(id);
        failed = true;
        if (id.startsWith(CONTAINER)) {
            finishImmediately = true;
            ContainerProcess cp = containerProcessesById.get(id);
            containerProcessStartLatches.get(cp).countDown();
        }
        else if (id.startsWith(TESTER)) {
            if (failFast) {
                finishImmediately = true;
            }
            testProcessFinishLatches.get(id).countDown();
        }
    }


	private void dumpEnvironment(Map<String, String> environtMap, String id) {
		getLog().debug(id+": Environment configuration");
		for (String key : environtMap.keySet()) {
            if (!privateEnvironmentVariables.contains(key)) {
			    getLog().debug("     " + key + "=" + environtMap.get(key));
            }
		}
	}

	
	private void validate() throws MojoExecutionException {
		int errorCount = 0;


        if (containerProcesses != null && containerProcesses.length > 0) {
            Set<String> ids = new HashSet<String>();
            for (ContainerProcess cp : containerProcesses) {
                if (cp.getId() == null || cp.getId().isEmpty()) {
                    getLog().error("containerProcess id not specified");
                    errorCount++;
                }
                else {
                    if (!ids.add(cp.getId())) {
                        getLog().error("There is more than one container process with id '"+cp.getId()+"'");
                        errorCount++;
                    }
                }
                if (cp.getCommand() == null || cp.getCommand().isEmpty()) {
                    getLog().error("containerProcess not specified");
                    errorCount++;
                }
                if (cp.getWorkingDir() == null || "".equals(cp.getWorkingDir())) {
                    getLog().error("containerProcessWorkingDir not specified");
                    errorCount++;
                }
            }
        }
        else {
            getLog().error("No container processes were specified");
            errorCount++;
        }

        if (testProcesses != null && testProcesses.length > 0) {
            Set<String> ids = new HashSet<String>();
            for (TestProcess tp : testProcesses) {
                if (tp.getId() == null || tp.getId().isEmpty()) {
                    getLog().error("testProcess id not specified");
                    errorCount++;
                }
                else {
                    if (!ids.add(tp.getId())) {
                        getLog().error("There is more than one test process with id '"+tp.getId()+"'");
                        errorCount++;
                    }
                }
                if (tp.getCommand() == null || tp.getCommand().isEmpty()) {
                    getLog().error("testProcess not specified");
                    errorCount++;
                }
                if (tp.getWorkingDir() == null || "".equals(tp.getWorkingDir())) {
                    getLog().error("testProcessWorkingDir not specified");
                    errorCount++;
                }
                if (tp.getStartupDelay() == null || "".equals(tp.getStartupDelay())) {
                    getLog().warn("No startup delay specified");
                }
            }
        }
        else {
            getLog().error("No test processes were specified");
        }
		if (errorCount > 0) {
			throw new MojoExecutionException(errorCount + " Configuration error(s) found. Aborting"); 
		}
	}
}
