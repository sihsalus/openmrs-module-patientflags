package org.openmrs.module.patientflags.evaluator;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.openmrs.Cohort;
import org.openmrs.api.APIException;
import org.openmrs.api.PatientService;
import org.openmrs.api.context.Context;
import org.openmrs.module.patientflags.Flag;
import org.openmrs.module.patientflags.api.FlagService;

/** Controls completion order without relying on thread scheduling luck. */
public class GroovyFlagEvaluatorCompletionTest {

	private MockedStatic<Context> context;
	private FlagService service;
	private Cohort cohort;
	private GroovyFlagEvaluatorThread evaluator;

	@Before
	public void setUp() {
		context = mockStatic(Context.class);
		service = mock(FlagService.class);
		PatientService patients = mock(PatientService.class);
		context.when(() -> Context.getService(FlagService.class)).thenReturn(service);
		context.when(Context::getPatientService).thenReturn(patients);
		when(service.getPrivileges()).thenReturn(Collections.emptyList());
		when(patients.getAllPatients(true)).thenReturn(Collections.emptyList());
		Flag flag = new Flag();
		flag.setCriteria("return testCohort");
		cohort = new Cohort();
		evaluator = new GroovyFlagEvaluatorThread(flag, cohort, null);
	}

	@After
	public void tearDown() {
		if (context != null) {
			context.close();
		}
	}

	@Test
	public void resultCompletedBeforeAReaderStartsShouldRemainAvailable() throws Exception {
		evaluator.run();
		assertNull(evaluator.getException());
		FutureTask<Cohort> result = new FutureTask<>(evaluator::fetchResultCohort);
		Thread reader = new Thread(result, "synthetic-completed-flag-reader");
		reader.start();
		try {
			assertSame(cohort, result.get(2, TimeUnit.SECONDS));
		} finally {
			result.cancel(true);
			reader.join(2000);
		}
	}

	@Test
	public void failureCompletedBeforeAReaderStartsShouldRemainAvailable() throws Exception {
		APIException failure = new APIException("Synthetic evaluation failure");
		when(service.getPrivileges()).thenThrow(failure);
		evaluator.run();
		assertSame(failure, evaluator.getException());
		FutureTask<Cohort> result = new FutureTask<>(evaluator::fetchResultCohort);
		Thread reader = new Thread(result, "synthetic-failed-flag-reader");
		reader.start();
		try {
			try {
				result.get(2, TimeUnit.SECONDS);
				fail("The evaluation failure must reach the reader");
			} catch (ExecutionException exception) {
				assertSame(failure, exception.getCause());
			}
		} finally {
			result.cancel(true);
			reader.join(2000);
		}
	}

	@Test
	public void completionShouldReleaseEveryWaitingReader() throws Exception {
		CountDownLatch entered = new CountDownLatch(2);
		FutureTask<Cohort> first = waitingReader(entered);
		FutureTask<Cohort> second = waitingReader(entered);
		Thread firstThread = new Thread(first, "synthetic-first-flag-reader");
		Thread secondThread = new Thread(second, "synthetic-second-flag-reader");
		firstThread.start();
		secondThread.start();
		try {
			assertTrue(entered.await(2, TimeUnit.SECONDS));
			// Each reader signals while holding the evaluator monitor. run() can
			// acquire it only after both readers enter fetchResultCohort's wait.
			evaluator.run();
			assertNull(evaluator.getException());
			assertSame(cohort, first.get(2, TimeUnit.SECONDS));
			assertSame(cohort, second.get(2, TimeUnit.SECONDS));
		} finally {
			first.cancel(true);
			second.cancel(true);
			firstThread.join(2000);
			secondThread.join(2000);
		}
	}

	private FutureTask<Cohort> waitingReader(CountDownLatch entered) {
		return new FutureTask<>(() -> {
			synchronized (evaluator) {
				entered.countDown();
				return evaluator.fetchResultCohort();
			}
		});
	}
}
