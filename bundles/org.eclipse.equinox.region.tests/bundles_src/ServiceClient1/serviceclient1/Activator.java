/*******************************************************************************
 * Copyright (c) 2011 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package serviceclient1;

import java.util.Dictionary;
import java.util.Hashtable;
import org.osgi.framework.*;
import org.osgi.util.tracker.ServiceTracker;
import org.osgi.util.tracker.ServiceTrackerCustomizer;

public class Activator implements BundleActivator {

	@Override
	public void start(final BundleContext context) throws Exception {
		new ServiceTracker<pkg2.a.A, pkg2.a.A>(context, pkg2.a.A.class, new ServiceTrackerCustomizer<pkg2.a.A, pkg2.a.A>() {

			@Override
			public pkg2.a.A addingService(ServiceReference<pkg2.a.A> reference) {
				Dictionary<String, Object> props = new Hashtable<String, Object>();
				props.put("bundle.id", context.getBundle().getBundleId());
				context.registerService(Boolean.class, Boolean.TRUE, props);
				return null;
			}

			@Override
			public void modifiedService(ServiceReference<pkg2.a.A> reference, pkg2.a.A service) {
				// TODO Auto-generated method stub

			}

			@Override
			public void removedService(ServiceReference<pkg2.a.A> reference, pkg2.a.A service) {
				// TODO Auto-generated method stub

			}
		}).open();
	}

	@Override
	public void stop(BundleContext context) throws Exception {
		// nothing
	}

}
