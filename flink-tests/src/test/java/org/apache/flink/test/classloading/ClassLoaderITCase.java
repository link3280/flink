/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.test.classloading;

import org.apache.flink.api.common.JobID;
import org.apache.flink.client.program.ClusterClient;
import org.apache.flink.client.program.MiniClusterClient;
import org.apache.flink.client.program.PackagedProgram;
import org.apache.flink.client.program.ProgramInvocationException;
import org.apache.flink.configuration.CheckpointingOptions;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.TaskManagerOptions;
import org.apache.flink.core.fs.Path;
import org.apache.flink.runtime.client.JobCancellationException;
import org.apache.flink.runtime.client.JobStatusMessage;
import org.apache.flink.runtime.jobgraph.JobStatus;
import org.apache.flink.runtime.testutils.MiniClusterResource;
import org.apache.flink.runtime.testutils.MiniClusterResourceConfiguration;
import org.apache.flink.streaming.util.TestStreamEnvironment;
import org.apache.flink.test.testdata.KMeansData;
import org.apache.flink.test.util.SuccessException;
import org.apache.flink.test.util.TestEnvironment;
import org.apache.flink.util.ExceptionUtils;
import org.apache.flink.util.TestLogger;

import org.apache.flink.shaded.guava18.com.google.common.collect.Lists;
import org.apache.flink.shaded.guava18.com.google.common.io.Files;

import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.rules.TemporaryFolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.Collection;
import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import scala.concurrent.duration.Deadline;
import scala.concurrent.duration.FiniteDuration;

import static org.hamcrest.CoreMatchers.isA;
import static org.junit.Assert.*;

/**
 * Test job classloader.
 */
public class ClassLoaderITCase extends TestLogger {

	private static final Logger LOG = LoggerFactory.getLogger(ClassLoaderITCase.class);

	private static final String INPUT_SPLITS_PROG_JAR_FILE = "customsplit-test-jar.jar";

	private static final String STREAMING_INPUT_SPLITS_PROG_JAR_FILE = "streaming-customsplit-test-jar.jar";

	private static final String STREAMING_PROG_JAR_FILE = "streamingclassloader-test-jar.jar";

	private static final String STREAMING_CHECKPOINTED_PROG_JAR_FILE = "streaming-checkpointed-classloader-test-jar.jar";

	private static final String KMEANS_JAR_PATH = "kmeans-test-jar.jar";

	private static final String USERCODETYPE_JAR_PATH = "usercodetype-test-jar.jar";

	private static final String CUSTOM_KV_STATE_JAR_PATH = "custom_kv_state-test-jar.jar";

	private static final String CHECKPOINTING_CUSTOM_KV_STATE_JAR_PATH = "checkpointing_custom_kv_state-test-jar.jar";

	private static final String CLASSLOADING_POLICY_JAR_PATH = "classloading_policy-test-jar.jar";


	@ClassRule
	public static final TemporaryFolder FOLDER = new TemporaryFolder();

	@Rule
	public ExpectedException expectedException = ExpectedException.none();

	private static MiniClusterResource miniClusterResource = null;

	private static final int parallelism = 4;

	@BeforeClass
	public static void setUp() throws Exception {

		Configuration config = new Configuration();

		// we need to use the "filesystem" state backend to ensure FLINK-2543 is not happening again.
		config.setString(CheckpointingOptions.STATE_BACKEND, "filesystem");
		config.setString(CheckpointingOptions.CHECKPOINTS_DIRECTORY,
				FOLDER.newFolder().getAbsoluteFile().toURI().toString());

		// Savepoint path
		config.setString(CheckpointingOptions.SAVEPOINT_DIRECTORY,
				FOLDER.newFolder().getAbsoluteFile().toURI().toString());

		// required as we otherwise run out of memory
		config.setString(TaskManagerOptions.LEGACY_MANAGED_MEMORY_SIZE, "80m");

		miniClusterResource = new MiniClusterResource(
			new MiniClusterResourceConfiguration.Builder()
				.setNumberTaskManagers(2)
				.setNumberSlotsPerTaskManager(2)
				.setConfiguration(config)
				.build());

		miniClusterResource.before();
	}

	@AfterClass
	public static void tearDownClass() {
		if (miniClusterResource != null) {
			miniClusterResource.after();
		}
	}

	@After
	public void tearDown() {
		TestStreamEnvironment.unsetAsContext();
		TestEnvironment.unsetAsContext();
	}

	@Test
	public void testCustomSplitJobWithCustomClassLoaderJar() throws ProgramInvocationException {

		PackagedProgram inputSplitTestProg = new PackagedProgram(new File(INPUT_SPLITS_PROG_JAR_FILE),
			new Configuration());

		TestEnvironment.setAsContext(
			miniClusterResource.getMiniCluster(),
			parallelism,
			Collections.singleton(new Path(INPUT_SPLITS_PROG_JAR_FILE)),
			Collections.emptyList());

		inputSplitTestProg.invokeInteractiveModeForExecution();
	}

	@Test
	public void testStreamingCustomSplitJobWithCustomClassLoader() throws ProgramInvocationException {
		PackagedProgram streamingInputSplitTestProg = new PackagedProgram(new File(STREAMING_INPUT_SPLITS_PROG_JAR_FILE),
			new Configuration());

		TestStreamEnvironment.setAsContext(
			miniClusterResource.getMiniCluster(),
			parallelism,
			Collections.singleton(new Path(STREAMING_INPUT_SPLITS_PROG_JAR_FILE)),
			Collections.emptyList());

		streamingInputSplitTestProg.invokeInteractiveModeForExecution();
	}

	@Test
	public void testCustomSplitJobWithCustomClassLoaderPath() throws IOException, ProgramInvocationException {
		URL classpath = new File(INPUT_SPLITS_PROG_JAR_FILE).toURI().toURL();
		PackagedProgram inputSplitTestProg2 = new PackagedProgram(new File(INPUT_SPLITS_PROG_JAR_FILE),
			new Configuration());

		TestEnvironment.setAsContext(
			miniClusterResource.getMiniCluster(),
			parallelism,
			Collections.emptyList(),
			Collections.singleton(classpath));

		inputSplitTestProg2.invokeInteractiveModeForExecution();
	}

	@Test
	public void testStreamingClassloaderJobWithCustomClassLoader() throws ProgramInvocationException {
		// regular streaming job
		PackagedProgram streamingProg = new PackagedProgram(new File(STREAMING_PROG_JAR_FILE),
			new Configuration(), new String[0]);

		TestStreamEnvironment.setAsContext(
			miniClusterResource.getMiniCluster(),
			parallelism,
			Collections.singleton(new Path(STREAMING_PROG_JAR_FILE)),
			Collections.emptyList());

		streamingProg.invokeInteractiveModeForExecution();
	}

	@Test
	public void testCheckpointedStreamingClassloaderJobWithCustomClassLoader() throws ProgramInvocationException {
		// checkpointed streaming job with custom classes for the checkpoint (FLINK-2543)
		// the test also ensures that user specific exceptions are serializable between JobManager <--> JobClient.
		PackagedProgram streamingCheckpointedProg = new PackagedProgram(new File(STREAMING_CHECKPOINTED_PROG_JAR_FILE),
			new Configuration(), new String[0]);

		TestStreamEnvironment.setAsContext(
			miniClusterResource.getMiniCluster(),
			parallelism,
			Collections.singleton(new Path(STREAMING_CHECKPOINTED_PROG_JAR_FILE)),
			Collections.emptyList());

		try {
			streamingCheckpointedProg.invokeInteractiveModeForExecution();
		} catch (Exception e) {
			// Program should terminate with a 'SuccessException':
			// the exception class is contained in the user-jar, but is not present on the maven classpath
			// the deserialization of the exception should thus fail here
			Optional<Throwable> exception = ExceptionUtils.findThrowable(e,
				candidate -> candidate.getClass().getName().equals("org.apache.flink.test.classloading.jar.CheckpointedStreamingProgram$SuccessException"));

			if (!exception.isPresent()) {
				// if this is achieved, either we failed due to another exception or the user-specific
				// exception is not serialized between JobManager and JobClient.
				throw e;
			}

			try {
				Class.forName(exception.get().getClass().getName());
				Assert.fail("Deserialization of user exception should have failed.");
			} catch (ClassNotFoundException expected) {
				// expected
			}
		}
	}

	@Test
	public void testKMeansJobWithCustomClassLoader() throws ProgramInvocationException {
		PackagedProgram kMeansProg = new PackagedProgram(
			new File(KMEANS_JAR_PATH),
			new Configuration(),
			new String[] {
				KMeansData.DATAPOINTS,
				KMeansData.INITIAL_CENTERS,
				"25"
			});

		TestEnvironment.setAsContext(
			miniClusterResource.getMiniCluster(),
			parallelism,
			Collections.singleton(new Path(KMEANS_JAR_PATH)),
			Collections.emptyList());

		kMeansProg.invokeInteractiveModeForExecution();
	}

	@Test
	public void testUserCodeTypeJobWithCustomClassLoader() throws ProgramInvocationException {
		PackagedProgram userCodeTypeProg = new PackagedProgram(new File(USERCODETYPE_JAR_PATH), new Configuration());

		TestEnvironment.setAsContext(
			miniClusterResource.getMiniCluster(),
			parallelism,
			Collections.singleton(new Path(USERCODETYPE_JAR_PATH)),
			Collections.emptyList());

		userCodeTypeProg.invokeInteractiveModeForExecution();
	}

	@Test
	public void testCheckpointingCustomKvStateJobWithCustomClassLoader() throws IOException, ProgramInvocationException {
		File checkpointDir = FOLDER.newFolder();
		File outputDir = FOLDER.newFolder();

		final PackagedProgram program = new PackagedProgram(
			new File(CHECKPOINTING_CUSTOM_KV_STATE_JAR_PATH),
			new Configuration(),
			new String[] {
				checkpointDir.toURI().toString(),
				outputDir.toURI().toString()
			});

		TestStreamEnvironment.setAsContext(
			miniClusterResource.getMiniCluster(),
			parallelism,
			Collections.singleton(new Path(CHECKPOINTING_CUSTOM_KV_STATE_JAR_PATH)),
			Collections.emptyList());

		expectedException.expectCause(
			Matchers.hasProperty("cause", isA(SuccessException.class)));

		program.invokeInteractiveModeForExecution();
	}

	/**
	 * Tests disposal of a savepoint, which contains custom user code KvState.
	 */
	@Test
	public void testDisposeSavepointWithCustomKvState() throws Exception {
		ClusterClient<?> clusterClient = new MiniClusterClient(new Configuration(), miniClusterResource.getMiniCluster());

		Deadline deadline = new FiniteDuration(100, TimeUnit.SECONDS).fromNow();

		File checkpointDir = FOLDER.newFolder();
		File outputDir = FOLDER.newFolder();

		final PackagedProgram program = new PackagedProgram(
				new File(CUSTOM_KV_STATE_JAR_PATH),
				new Configuration(),
				new String[] {
						String.valueOf(parallelism),
						checkpointDir.toURI().toString(),
						"5000",
						outputDir.toURI().toString()
				});

		TestStreamEnvironment.setAsContext(
			miniClusterResource.getMiniCluster(),
			parallelism,
			Collections.singleton(new Path(CUSTOM_KV_STATE_JAR_PATH)),
			Collections.emptyList()
		);

		// Execute detached
		Thread invokeThread = new Thread(() -> {
			try {
				program.invokeInteractiveModeForExecution();
			} catch (ProgramInvocationException ex) {
				if (ex.getCause() == null ||
					!(ex.getCause() instanceof JobCancellationException)) {
					ex.printStackTrace();
				}
			}
		});

		LOG.info("Starting program invoke thread");
		invokeThread.start();

		// The job ID
		JobID jobId = null;

		LOG.info("Waiting for job status running.");

		// Wait for running job
		while (jobId == null && deadline.hasTimeLeft()) {

			Collection<JobStatusMessage> jobs = clusterClient.listJobs().get(deadline.timeLeft().toMillis(), TimeUnit.MILLISECONDS);
			for (JobStatusMessage job : jobs) {
				if (job.getJobState() == JobStatus.RUNNING) {
					jobId = job.getJobId();
					LOG.info("Job running. ID: " + jobId);
					break;
				}
			}

			// Retry if job is not available yet
			if (jobId == null) {
				Thread.sleep(100L);
			}
		}

		// Trigger savepoint
		String savepointPath = null;
		for (int i = 0; i < 20; i++) {
			LOG.info("Triggering savepoint (" + (i + 1) + "/20).");
			try {
				savepointPath = clusterClient.triggerSavepoint(jobId, null)
					.get(deadline.timeLeft().toMillis(), TimeUnit.MILLISECONDS);
			} catch (Exception cause) {
				LOG.info("Failed to trigger savepoint. Retrying...", cause);
				// This can fail if the operators are not opened yet
				Thread.sleep(500);
			}
		}

		assertNotNull("Failed to trigger savepoint", savepointPath);

		clusterClient.disposeSavepoint(savepointPath).get();

		clusterClient.cancel(jobId);

		// make sure, the execution is finished to not influence other test methods
		invokeThread.join(deadline.timeLeft().toMillis());
		assertFalse("Program invoke thread still running", invokeThread.isAlive());
	}

	@Test
	public void testProgramWithChildFirstClassLoader() throws IOException, ProgramInvocationException {
		// We have two files named test-resource in src/resource (parent classloader classpath) and
		// tmp folders (child classloader classpath) respectively.
		String childResourceDirName = "child";
		String testResourceName = "test-resource";
		File childResourceDir = FOLDER.newFolder(childResourceDirName);
		File childResource = new File(childResourceDir.getAbsolutePath() + File.separator + testResourceName);
		Files.touch(childResource);

		TestStreamEnvironment.setAsContext(
			miniClusterResource.getMiniCluster(),
			parallelism,
			Collections.singleton(new Path(CLASSLOADING_POLICY_JAR_PATH)),
			Collections.emptyList());

		// default child first classloading
		final PackagedProgram childFirstProgram = new PackagedProgram(
				new File(CLASSLOADING_POLICY_JAR_PATH),
				Lists.newArrayList(childResourceDir.toURI().toURL()),
				new Configuration(),
				new String[]{testResourceName, childResourceDirName}
			);

		final ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
		Thread.currentThread().setContextClassLoader(childFirstProgram.getUserCodeClassLoader());
		try {
			childFirstProgram.invokeInteractiveModeForExecution();
		} finally {
			Thread.currentThread().setContextClassLoader(contextClassLoader);
		}
	}

	@Test
	public void testProgramWithParentFirstClassLoader() throws IOException, ProgramInvocationException {
		// We have two files named test-resource in src/resource (parent classloader classpath) and
		// tmp folders (child classloader classpath) respectively.
		String childResourceDirName = "child";
		String testResourceName = "test-resource";
		File childResourceDir = FOLDER.newFolder(childResourceDirName);
		File childResource = new File(childResourceDir.getAbsolutePath() + File.separator + testResourceName);
		Files.touch(childResource);

		TestStreamEnvironment.setAsContext(
			miniClusterResource.getMiniCluster(),
			parallelism,
			Collections.singleton(new Path(CLASSLOADING_POLICY_JAR_PATH)),
			Collections.emptyList());
		final ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();

		// parent-first classloading
		Configuration parentFirstConf = new Configuration();
		parentFirstConf.setString("classloader.resolve-order", "parent-first");
		final PackagedProgram parentFirstProgram = new PackagedProgram(
			new File(CLASSLOADING_POLICY_JAR_PATH),
			Lists.newArrayList(childResourceDir.toURI().toURL()),
			parentFirstConf,
			new String[]{testResourceName, "test-classes"}
		);

		Thread.currentThread().setContextClassLoader(parentFirstProgram.getUserCodeClassLoader());
		try {
			parentFirstProgram.invokeInteractiveModeForExecution();
		} finally {
			Thread.currentThread().setContextClassLoader(contextClassLoader);
		}
	}
}
