/*******************************************************************************
 * Copyright (c) 2008 IBM Corporation and others
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.equinox.useradmin.tests;

import junit.framework.TestCase;
import org.eclipse.equinox.compendium.tests.Activator;
import org.osgi.framework.ServiceReference;
import org.osgi.service.useradmin.*;

public class GroupTest extends TestCase {

	private UserAdmin userAdmin;
	private ServiceReference userAdminReference;

	boolean locked = false;
	Object lock = new Object();

	protected void setUp() throws Exception {
		Activator.getBundle(Activator.BUNDLE_USERADMIN).start();
		userAdminReference = Activator.getBundleContext().getServiceReference(UserAdmin.class.getName());
		userAdmin = (UserAdmin) Activator.getBundleContext().getService(userAdminReference);
	}

	protected void tearDown() throws Exception {
		Activator.getBundleContext().ungetService(userAdminReference);
		Activator.getBundle(Activator.BUNDLE_USERADMIN).stop();
	}

	public void testGroupCreateAndRemove() throws Exception {
		Group group = (Group) userAdmin.createRole("testGroupCreateAndRemove", Role.GROUP); //$NON-NLS-1$
		assertNotNull(group);
		assertEquals("testGroupCreateAndRemove", group.getName()); //$NON-NLS-1$
		assertTrue(group.getType() == Role.GROUP);
		assertTrue(userAdmin.removeRole("testGroupCreateAndRemove")); //$NON-NLS-1$
		assertNull(userAdmin.getRole("testGroupCreateAndRemove")); //$NON-NLS-1$
	}

	public void testGroupAddingAndRemovingMembers() throws Exception {
		Group group = (Group) userAdmin.createRole("testGroupAddingAndRemovingMembers", Role.GROUP); //$NON-NLS-1$
		assertNotNull(group);
		assertEquals("testGroupAddingAndRemovingMembers", group.getName()); //$NON-NLS-1$
		assertTrue(group.getType() == Role.GROUP);
		assertNull(group.getMembers());

		User user = (User) userAdmin.createRole("testRole1", Role.USER); //$NON-NLS-1$
		assertNotNull(user);

		group.addMember(user);
		assertEquals(group.getMembers().length, 1);
		assertNull(group.getRequiredMembers());
		group.removeMember(user);
		assertNull(group.getMembers());
		assertNull(group.getRequiredMembers());
		group.addRequiredMember(user);
		assertNull(group.getMembers());
		assertEquals(group.getRequiredMembers().length, 1);

		userAdmin.removeRole("testRole1"); //$NON-NLS-1$
		assertNull(userAdmin.getRole("testRole1")); //$NON-NLS-1$
	}

}
