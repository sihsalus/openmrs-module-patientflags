package org.openmrs.module.patientflags.task;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.openmrs.Cohort;
import org.openmrs.Patient;
import org.openmrs.api.context.Context;
import org.openmrs.api.context.Daemon;
import org.openmrs.module.DaemonToken;
import org.openmrs.module.patientflags.Flag;
import org.openmrs.module.patientflags.api.FlagService;

/** Verifies delayed execution without depending on thread timing or a database. */
public class PatientFlagTaskSchedulingTest {

	private final List<Runnable> queued = new ArrayList<>();
	private FlagService service;
	private MockedStatic<Context> context;
	private MockedStatic<Daemon> daemon;

	@Before
	public void setUp() {
		service = mock(FlagService.class);
		context = mockStatic(Context.class);
		daemon = mockStatic(Daemon.class);
		DaemonToken token = new DaemonToken("synthetic-scheduling-test");
		PatientFlagTask.setDaemonToken(token);
		context.when(() -> Context.getService(FlagService.class)).thenReturn(service);
		daemon.when(() -> Daemon.runInDaemonThread(any(Runnable.class), same(token))).thenAnswer(invocation -> {
			queued.add(invocation.getArgument(0));
			return null;
		});
		when(service.generateFlagsForPatient(any(Patient.class), anyMap())).thenReturn(Collections.emptyList());
		when(service.getFlaggedPatients(any(Flag.class), anyMap())).thenReturn(new Cohort());
	}

	@After
	public void tearDown() {
		PatientFlagTask.setDaemonToken(null);
		if (daemon != null) {
			daemon.close();
		}
		if (context != null) {
			context.close();
		}
	}

	@Test
	public void queuedPatientsShouldKeepTheirOwnTargetsWhenExecutedInReverseOrder() {
		Patient first = new Patient(101);
		Patient second = new Patient(102);
		PatientFlagTask task = new PatientFlagTask();
		task.generatePatientFlags(first);
		task.generatePatientFlags(second);

		assertEquals(2, queued.size());
		queued.get(1).run();
		queued.get(0).run();

		verify(service).deletePatientFlagsForPatient(first);
		verify(service).deletePatientFlagsForPatient(second);
		verify(service).generateFlagsForPatient(same(first), anyMap());
		verify(service).generateFlagsForPatient(same(second), anyMap());
		verify(service, never()).deletePatientFlagsForFlag(any(Flag.class));
	}

	@Test
	public void queuedFlagsShouldKeepTheirOwnTargetsWhenExecutedInReverseOrder() {
		Flag first = flag("first");
		Flag second = flag("second");
		PatientFlagTask task = new PatientFlagTask();
		task.generatePatientFlags(first);
		task.generatePatientFlags(second);

		assertEquals(2, queued.size());
		queued.get(1).run();
		queued.get(0).run();

		verify(service).deletePatientFlagsForFlag(first);
		verify(service).deletePatientFlagsForFlag(second);
		verify(service).getFlaggedPatients(same(first), anyMap());
		verify(service).getFlaggedPatients(same(second), anyMap());
		verify(service, never()).deletePatientFlagsForPatient(any(Patient.class));
	}

	@Test
	public void aFlagRequestShouldNotReuseThePatientFromAnEarlierRequest() {
		Patient patient = new Patient(103);
		Flag flag = flag("independent-flag");
		PatientFlagTask task = new PatientFlagTask();
		task.generatePatientFlags(patient);
		task.generatePatientFlags(flag);

		assertEquals(2, queued.size());
		queued.get(0).run();
		queued.get(1).run();

		verify(service).deletePatientFlagsForPatient(patient);
		verify(service).deletePatientFlagsForFlag(flag);
		verify(service).generateFlagsForPatient(same(patient), anyMap());
		verify(service).getFlaggedPatients(same(flag), anyMap());
	}

	@Test
	public void noWorkShouldBeScheduledWithoutTheModuleDaemonToken() {
		PatientFlagTask.setDaemonToken(null);
		PatientFlagTask task = new PatientFlagTask();
		task.generatePatientFlags(new Patient(104));
		task.generatePatientFlags(flag("not-scheduled"));

		assertTrue(queued.isEmpty());
		daemon.verifyNoInteractions();
		verify(service, never()).deletePatientFlagsForPatient(any(Patient.class));
		verify(service, never()).deletePatientFlagsForFlag(any(Flag.class));
	}

	private Flag flag(String name) {
		Flag flag = new Flag();
		flag.setName(name);
		flag.setEnabled(true);
		flag.setRetired(false);
		return flag;
	}
}
