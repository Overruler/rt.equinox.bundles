/*******************************************************************************
 * Copyright (c) 2015 Raymond Augé and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Raymond Augé <raymond.auge@liferay.com> - Bug 436698
 ******************************************************************************/

package org.eclipse.equinox.http.servlet.internal.context;

import java.security.AccessController;
import java.util.*;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.atomic.AtomicReference;
import javax.servlet.*;
import javax.servlet.Filter;
import javax.servlet.http.*;
import org.eclipse.equinox.http.servlet.internal.HttpServiceRuntimeImpl;
import org.eclipse.equinox.http.servlet.internal.customizer.*;
import org.eclipse.equinox.http.servlet.internal.error.RegisteredFilterException;
import org.eclipse.equinox.http.servlet.internal.registration.*;
import org.eclipse.equinox.http.servlet.internal.registration.FilterRegistration;
import org.eclipse.equinox.http.servlet.internal.registration.ServletRegistration;
import org.eclipse.equinox.http.servlet.internal.servlet.*;
import org.eclipse.equinox.http.servlet.internal.util.*;
import org.osgi.framework.*;
import org.osgi.service.http.context.ServletContextHelper;
import org.osgi.service.http.runtime.dto.*;
import org.osgi.service.http.whiteboard.HttpWhiteboardConstants;
import org.osgi.util.tracker.ServiceTracker;

/**
 * @author Raymond Augé
 */
public class ContextController {

	public static final class ServiceHolder<S> implements Comparable<ServiceHolder<?>> {
		final ServiceObjects<S> serviceObjects;
		final S service;
		final Bundle bundle;
		final long serviceId;
		final int serviceRanking;
		public ServiceHolder(ServiceObjects<S> serviceObjects) {
			this.serviceObjects = serviceObjects;
			this.bundle = serviceObjects.getServiceReference().getBundle();
			this.service = serviceObjects.getService();
			this.serviceId = (Long) serviceObjects.getServiceReference().getProperty(Constants.SERVICE_ID);
			Integer rankProp = (Integer) serviceObjects.getServiceReference().getProperty(Constants.SERVICE_RANKING);
			this.serviceRanking = rankProp == null ? 0 : rankProp.intValue();
		}
		public ServiceHolder(S service, Bundle bundle, long serviceId, int serviceRanking) {
			this.service = service;
			this.bundle = bundle;
			this.serviceObjects = null;
			this.serviceId = serviceId;
			this.serviceRanking = serviceRanking;
		}
		public S get() {
			return service;
		}

		public Bundle getBundle() {
			return bundle;
		}
		public void release() {
			if (serviceObjects != null && service != null) {
				try {
					serviceObjects.ungetService(service);
				} catch (IllegalStateException e) {
					// this can happen if the whiteboard bundle is in the process of stopping
					// and the framework is in the middle of auto-unregistering any services
					// the bundle forgot to unregister on stop
				}
			}
		}

		public ServiceReference<S> getServiceReference() {
			return serviceObjects == null ? null : serviceObjects.getServiceReference();
		}
		@Override
		public int compareTo(ServiceHolder<?> o) {
			final int thisRanking = serviceRanking;
			final int otherRanking = o.serviceRanking;
			if (thisRanking != otherRanking) {
				if (thisRanking < otherRanking) {
					return 1;
				}
				return -1;
			}
			final long thisId = this.serviceId;
			final long otherId = o.serviceId;
			if (thisId == otherId) {
				return 0;
			}
			if (thisId < otherId) {
				return -1;
			}
			return 1;
		}
	}

	public ContextController(
		BundleContext trackingContextParam, BundleContext consumingContext,
		ServiceReference<ServletContextHelper> servletContextHelperRef,
		ProxyContext proxyContext, HttpServiceRuntimeImpl httpServiceRuntime,
		String contextName, String contextPath, long serviceId,
		Map<String, Object> attributes) {

		this.servletContextHelperRef = servletContextHelperRef;
		this.proxyContext = proxyContext;
		this.httpServiceRuntime = httpServiceRuntime;
		this.contextName = contextName;
		this.contextPath = contextPath;
		this.contextServiceId = serviceId;

		attributes = new HashMap<String, Object>(attributes);

		this.attributes = attributes;

		Map<String, String> attributeStringValues = new HashMap<String, String>();

		for (String key : this.attributes.keySet()) {
			attributeStringValues.put(key, String.valueOf(attributes.get(key)));
		}

		this.initParams = Collections.unmodifiableMap(attributeStringValues);

		this.trackingContext = trackingContextParam;
		this.consumingContext = consumingContext;
		

		listenerServiceTracker = new ServiceTracker<EventListener, AtomicReference<ListenerRegistration>>(
			trackingContext, httpServiceRuntime.getListenerFilter(),
			new ContextListenerTrackerCustomizer(
				trackingContext, httpServiceRuntime, this));

		listenerServiceTracker.open();

		filterServiceTracker = new ServiceTracker<Filter, AtomicReference<FilterRegistration>>(
			trackingContext, httpServiceRuntime.getFilterFilter(),
			new ContextFilterTrackerCustomizer(
				trackingContext, httpServiceRuntime, this));

		filterServiceTracker.open();

		servletServiceTracker =  new ServiceTracker<Servlet, AtomicReference<ServletRegistration>>(
			trackingContext, httpServiceRuntime.getServletFilter(),
			new ContextServletTrackerCustomizer(
				trackingContext, httpServiceRuntime, this));

		servletServiceTracker.open();

		resourceServiceTracker = new ServiceTracker<Servlet, AtomicReference<ResourceRegistration>>(
			trackingContext, httpServiceRuntime.getResourceFilter(),
			new ContextResourceTrackerCustomizer(
				trackingContext, httpServiceRuntime, this));

		resourceServiceTracker.open();
	}

	public FilterRegistration addFilterRegistration(ServiceReference<Filter> filterRef) throws ServletException {
		checkShutdown();

		ServiceHolder<Filter> filterHolder = new ServiceHolder<Filter>(consumingContext.getServiceObjects(filterRef));
		Filter filter = filterHolder.get();
		FilterRegistration registration = null;
		boolean addedRegisteredObject = false;
		try {
			if (filter == null) {
				throw new IllegalArgumentException("Filter cannot be null");
			}
			addedRegisteredObject = httpServiceRuntime.getRegisteredObjects().add(filter);
			if (addedRegisteredObject) {
				registration = doAddFilterRegistration(filterHolder, filterRef);
			}
		} finally {
			if (registration == null) {
				filterHolder.release();
				if (addedRegisteredObject) {
					httpServiceRuntime.getRegisteredObjects().remove(filter);
				}
			}
		}
		return registration;
	}

	private FilterRegistration doAddFilterRegistration(ServiceHolder<Filter> filterHolder, ServiceReference<Filter> filterRef) throws ServletException {

		boolean asyncSupported = ServiceProperties.parseBoolean(
			filterRef, HttpWhiteboardConstants.HTTP_WHITEBOARD_FILTER_ASYNC_SUPPORTED);

		List<String> dispatcherList = StringPlus.from(
			filterRef.getProperty(
				HttpWhiteboardConstants.HTTP_WHITEBOARD_FILTER_DISPATCHER));
		String[] dispatchers = dispatcherList.toArray(
			new String[dispatcherList.size()]);
		Long serviceId = (Long)filterRef.getProperty(
			Constants.SERVICE_ID);
		Integer filterPriority = (Integer)filterRef.getProperty(
			Constants.SERVICE_RANKING);
		if (filterPriority == null) {
			filterPriority = Integer.valueOf(0);
		}
		Map<String, String> filterInitParams = ServiceProperties.parseInitParams(
			filterRef, Const.FILTER_INIT_PREFIX);
		List<String> patternList = StringPlus.from(
			filterRef.getProperty(
				HttpWhiteboardConstants.HTTP_WHITEBOARD_FILTER_PATTERN));
		String[] patterns = patternList.toArray(new String[patternList.size()]);
		List<String> servletList = StringPlus.from(
			filterRef.getProperty(
				HttpWhiteboardConstants.HTTP_WHITEBOARD_FILTER_SERVLET));
		String[] servletNames = servletList.toArray(new String[servletList.size()]);

		// TODO add regex support - 140.5

		// List<String> regexList = StringPlus.from(
		//	serviceReference.getProperty(
		//		HttpWhiteboardConstants.HTTP_WHITEBOARD_FILTER_REGEX));

		// String[] regex = regexList.toArray(new String[regexList.size()]);

		String name = ServiceProperties.parseName(filterRef.getProperty(
			HttpWhiteboardConstants.HTTP_WHITEBOARD_FILTER_NAME), filterHolder.get());

		Filter filter = filterHolder.get();

		if (((patterns == null) || (patterns.length == 0)) &&
			((servletNames == null) || servletNames.length == 0)) {

			throw new IllegalArgumentException(
				"Patterns or servletNames must contain a value.");
		}

		if (patterns != null) {
			for (String pattern : patterns) {
				checkPattern(pattern);
			}
		}

		if (filter == null) {
			throw new IllegalArgumentException("Filter cannot be null");
		}

		if (name == null) {
			name = filter.getClass().getName();
		}

		for (FilterRegistration filterRegistration : filterRegistrations) {
			if (filterRegistration.getT().equals(filter)) {
				throw new RegisteredFilterException(filter);
			}
		}

		dispatchers = checkDispatcher(dispatchers);

		FilterDTO filterDTO = new FilterDTO();

		filterDTO.asyncSupported = asyncSupported;
		filterDTO.dispatcher = sort(dispatchers);
		filterDTO.initParams = filterInitParams;
		filterDTO.name = name;
		filterDTO.patterns = sort(patterns);
		// TODO
		//filterDTO.regexps = sort(regexps);
		filterDTO.serviceId = serviceId;
		filterDTO.servletContextId = contextServiceId;
		filterDTO.servletNames = sort(servletNames);

		ServletContextHelper curServletContextHelper = getServletContextHelper(
			filterHolder.getBundle());

		ServletContext servletContext = createServletContext(
			filterHolder.getBundle(), curServletContextHelper);
		FilterRegistration newRegistration  = new FilterRegistration(
			filterHolder, filterDTO, filterPriority, this);
		FilterConfig filterConfig = new FilterConfigImpl(
			name, filterInitParams, servletContext);

		newRegistration.init(filterConfig);

		filterRegistrations.add(newRegistration);
		return newRegistration;
	}

	public ListenerRegistration addListenerRegistration(ServiceReference<EventListener> listenerRef) throws ServletException {
		
		checkShutdown();

		ServiceHolder<EventListener> listenerHolder = new ServiceHolder<EventListener>(consumingContext.getServiceObjects(listenerRef));
		EventListener listener = listenerHolder.get();
		ListenerRegistration registration = null;
		try {
			if (listener == null) {
				throw new IllegalArgumentException("EventListener cannot be null");
			}
			registration = doAddListenerRegistration(listenerHolder, listenerRef);
		} finally {
			if (registration == null) {
				listenerHolder.release();
			}
		}
		return registration;
	}

	private ListenerRegistration doAddListenerRegistration(
		ServiceHolder<EventListener> listenerHolder,
		ServiceReference<EventListener> listenerRef) throws ServletException {


		EventListener eventListener = listenerHolder.get();
		List<Class<? extends EventListener>> classes = getListenerClasses(
			eventListener);

		if (classes.isEmpty()) {
			throw new IllegalArgumentException(
				"EventListener does not implement a supported type.");
		}

		for (ListenerRegistration listenerRegistration : listenerRegistrations) {
			if (listenerRegistration.getT().equals(eventListener)) {
				throw new ServletException(
					"EventListener has already been registered.");
			}
		}

		ListenerDTO listenerDTO = new ListenerDTO();

		listenerDTO.serviceId = (Long) listenerRef.getProperty(Constants.SERVICE_ID);
		listenerDTO.servletContextId = contextServiceId;
		listenerDTO.types = asStringArray(classes);

		ServletContextHelper curServletContextHelper = getServletContextHelper(
			listenerHolder.getBundle());

		ServletContext servletContext = createServletContext(
			listenerHolder.getBundle(), curServletContextHelper);
		ListenerRegistration listenerRegistration = new ListenerRegistration(
			listenerHolder, classes, listenerDTO, servletContext, this);

		if (classes.contains(ServletContextListener.class)) {
			ServletContextListener servletContextListener =
				(ServletContextListener)listenerRegistration.getT();

			servletContextListener.contextInitialized(
				new ServletContextEvent(servletContext));
		}

		listenerRegistrations.add(listenerRegistration);

		eventListeners.put(classes, listenerRegistration);

		return listenerRegistration;
	}

	public ResourceRegistration addResourceRegistration(ServiceReference<?> resourceRef) {

		checkShutdown();

		boolean legacyMatching = ServiceProperties.parseBoolean(resourceRef, Const.EQUINOX_LEGACY_MATCHING_PROP);
		Integer rankProp = (Integer) resourceRef.getProperty(Constants.SERVICE_RANKING);
		int serviceRanking = rankProp == null ? 0 : rankProp.intValue();
		List<String> patternList = StringPlus.from(
			resourceRef.getProperty(
				HttpWhiteboardConstants.HTTP_WHITEBOARD_RESOURCE_PATTERN));
		String[] patterns = patternList.toArray(new String[patternList.size()]);
		Long serviceId = (Long)resourceRef.getProperty(
			Constants.SERVICE_ID);
		if (legacyMatching) {
			// this is a legacy registration; use a negative id for the DTO
			serviceId = -serviceId;
		}
		String prefix = (String)resourceRef.getProperty(
			HttpWhiteboardConstants.HTTP_WHITEBOARD_RESOURCE_PREFIX);

		checkPrefix(prefix);

		if ((patterns == null) || (patterns.length < 1)) {
			throw new IllegalArgumentException(
				"Patterns must contain a value.");
		}

		for (String pattern : patterns) {
			checkPattern(pattern);
		}

		Bundle bundle = resourceRef.getBundle();
		ServletContextHelper curServletContextHelper = getServletContextHelper(
			bundle);
		Servlet servlet = new ResourceServlet(
			prefix, curServletContextHelper, AccessController.getContext());

		ResourceDTO resourceDTO = new ResourceDTO();

		resourceDTO.patterns = sort(patterns);
		resourceDTO.prefix = prefix;
		resourceDTO.serviceId = serviceId;
		resourceDTO.servletContextId = contextServiceId;

		ServletContext servletContext = createServletContext(
			bundle, curServletContextHelper);
		ResourceRegistration resourceRegistration = new ResourceRegistration(
			new ServiceHolder<Servlet>(servlet, bundle, serviceId, serviceRanking),
			resourceDTO, curServletContextHelper, this, legacyMatching);
		ServletConfig servletConfig = new ServletConfigImpl(
			resourceRegistration.getName(), new HashMap<String, String>(),
			servletContext);

		try {
			resourceRegistration.init(servletConfig);
		}
		catch (ServletException e) {
			return null;
		}

		endpointRegistrations.add(resourceRegistration);

		return resourceRegistration;
	}

	public ServletRegistration addServletRegistration(ServiceReference<Servlet> servletRef) throws ServletException {
		
		checkShutdown();

		ServiceHolder<Servlet> servletHolder = new ServiceHolder<Servlet>(consumingContext.getServiceObjects(servletRef));
		Servlet servlet = servletHolder.get();
		ServletRegistration registration = null;
		boolean addedRegisteredObject = false;
		try {
			if (servlet == null) {
				throw new IllegalArgumentException("Servlet cannot be null");
			}
			addedRegisteredObject = httpServiceRuntime.getRegisteredObjects().add(servlet);
			if (addedRegisteredObject) {
				registration = doAddServletRegistration(servletHolder, servletRef);
			}
		} finally {
			if (registration == null) {
				servletHolder.release();
				if (addedRegisteredObject) {
					httpServiceRuntime.getRegisteredObjects().remove(servlet);
				}
			}
		}
		return registration;
	}

	private ServletRegistration doAddServletRegistration(ServiceHolder<Servlet> servletHolder, ServiceReference<Servlet> servletRef) throws ServletException {

		boolean asyncSupported = ServiceProperties.parseBoolean(
			servletRef,	HttpWhiteboardConstants.HTTP_WHITEBOARD_SERVLET_ASYNC_SUPPORTED);
		boolean legacyMatching = ServiceProperties.parseBoolean(servletRef, Const.EQUINOX_LEGACY_MATCHING_PROP);
		List<String> errorPageList = StringPlus.from(
			servletRef.getProperty(HttpWhiteboardConstants.HTTP_WHITEBOARD_SERVLET_ERROR_PAGE));
		String[] errorPages = errorPageList.toArray(new String[errorPageList.size()]);
		Map<String, String> servletInitParams = ServiceProperties.parseInitParams(
			servletRef, Const.SERVLET_INIT_PREFIX);
		List<String> patternList = StringPlus.from(
			servletRef.getProperty(HttpWhiteboardConstants.HTTP_WHITEBOARD_SERVLET_PATTERN));
		String[] patterns = patternList.toArray(new String[patternList.size()]);
		Long serviceId = (Long)servletRef.getProperty(Constants.SERVICE_ID);
		if (legacyMatching) {
			// this is a legacy registration; use a negative id for the DTO
			serviceId = -serviceId;
		}
		String servletName = ServiceProperties.parseName(
			servletRef.getProperty(
				HttpWhiteboardConstants.HTTP_WHITEBOARD_SERVLET_NAME), servletHolder.get());

		if (((patterns == null) || (patterns.length == 0)) &&
			((errorPages == null) || errorPages.length == 0)) {
			throw new IllegalArgumentException(
				"Either patterns or errorPages must contain a value.");
		}

		if (patterns != null) {
			for (String pattern : patterns) {
				checkPattern(pattern);
			}
		}

		ServletDTO servletDTO = new ServletDTO();

		servletDTO.asyncSupported = asyncSupported;
		servletDTO.initParams = servletInitParams;
		servletDTO.name = servletName;
		servletDTO.patterns = sort(patterns);
		servletDTO.serviceId = serviceId;
		servletDTO.servletContextId = contextServiceId;
		servletDTO.servletInfo = servletHolder.get().getServletInfo();

		ErrorPageDTO errorPageDTO = null;

		if ((errorPages != null) && (errorPages.length > 0)) {
			errorPageDTO = new ErrorPageDTO();

			errorPageDTO.asyncSupported = asyncSupported;
			long[] errorCodes = new long[0];
			List<String> exceptions = new ArrayList<String>();

			for(String errorPage : errorPages) {
				try {
					long longValue = Long.parseLong(errorPage);

					errorCodes = Arrays.copyOf(errorCodes, errorCodes.length + 1);

					errorCodes[errorCodes.length - 1] = longValue;
				}
				catch (NumberFormatException nfe) {
					exceptions.add(errorPage);
				}
			}

			errorPageDTO.errorCodes = errorCodes;
			errorPageDTO.exceptions = exceptions.toArray(new String[exceptions.size()]);
			errorPageDTO.initParams = servletInitParams;
			errorPageDTO.name = servletName;
			errorPageDTO.serviceId = serviceId;
			errorPageDTO.servletContextId = contextServiceId;
			errorPageDTO.servletInfo = servletHolder.get().getServletInfo();
		}

		ServletContextHelper curServletContextHelper = getServletContextHelper(
			servletHolder.getBundle());

		ServletContext servletContext = createServletContext(
			servletHolder.getBundle(), curServletContextHelper);
		ServletRegistration servletRegistration = new ServletRegistration(
			servletHolder, servletDTO, errorPageDTO, curServletContextHelper, this, legacyMatching);
		ServletConfig servletConfig = new ServletConfigImpl(
			servletName, servletInitParams, servletContext);

		servletRegistration.init(servletConfig);

		endpointRegistrations.add(servletRegistration);

		return servletRegistration;
	}

	public void destroy() {
		resourceServiceTracker.close();
		servletServiceTracker.close();
		filterServiceTracker.close();
		listenerServiceTracker.close();

		endpointRegistrations.clear();
		filterRegistrations.clear();
		listenerRegistrations.clear();
		eventListeners.clear();
		proxyContext.destroy();

		shutdown = true;
	}

	public String getContextName() {
		checkShutdown();

		return contextName;
	}

	public String getContextPath() {
		checkShutdown();

		return contextPath;
	}

	public DispatchTargets getDispatchTargets(
		HttpServletRequest request, String servletName, String requestURI,
		String servletPath, String pathInfo, String extension, Match match,
		RequestInfoDTO requestInfoDTO) {

		checkShutdown();

		getProxyContext().initializeServletPath(request);

		EndpointRegistration<?> endpointRegistration = null;
		String pattern = null;

		for (EndpointRegistration<?> curEndpointRegistration : endpointRegistrations) {
			if ((pattern = curEndpointRegistration.match(
					servletName, servletPath, pathInfo, extension, match)) != null) {

				endpointRegistration = curEndpointRegistration;

				break;
			}
		}

		if (endpointRegistration == null) {
			return null;
		}

		endpointRegistration.addReference();

		addEnpointRegistrationsToRequestInfo(
			endpointRegistration, requestInfoDTO);

		if (filterRegistrations.isEmpty()) {
			return new DispatchTargets(
				this, endpointRegistration, servletPath, pathInfo, pattern);
		}

		String originalServletPath = servletPath;
		String originalPathInfo = pathInfo;

		List<FilterRegistration> matchingFilterRegistrations =
			new ArrayList<FilterRegistration>();

		for (Match curMatch : Match.values()) {
			collectFilters(
				matchingFilterRegistrations, servletName, requestURI, curMatch);
		}

		addFilterRegistrationsToRequestInfo(
			matchingFilterRegistrations, requestInfoDTO);

		return new DispatchTargets(
			this, endpointRegistration, matchingFilterRegistrations,
			originalServletPath, originalPathInfo, pattern);
	}

	private void collectFilters(
		List<FilterRegistration> matchingFilterRegistrations,
		String servletName, String requestURI, Match match) {

		String servletPath = requestURI;
		String pathInfo = "";
		String extension = null;

		int pos = -1;

		if (requestURI != null) {
			pos = requestURI.lastIndexOf('/');

			if (match == Match.EXTENSION) {
				int x = requestURI.lastIndexOf('.');

				if (x != -1) {
					extension = requestURI.substring(x + 1);
				}

				if (extension == null) {
					return;
				}
			}
		}

		do {
			for (FilterRegistration filterRegistration : filterRegistrations) {
				if ((filterRegistration.match(
						servletName, servletPath, pathInfo, extension, match) != null) &&
					!matchingFilterRegistrations.contains(filterRegistration)) {

					matchingFilterRegistrations.add(filterRegistration);

					filterRegistration.addReference();
				}
			}

			if (pos > -1) {
				String newServletPath = requestURI.substring(0, pos);
				pathInfo = requestURI.substring(pos);
				servletPath = newServletPath;
				pos = servletPath.lastIndexOf('/');

				continue;
			}

			break;
		}
		while (true);
	}

	public Set<EndpointRegistration<?>> getEndpointRegistrations() {
		checkShutdown();

		return endpointRegistrations;
	}

	public EventListeners getEventListeners() {
		checkShutdown();

		return eventListeners;
	}

	public Set<FilterRegistration> getFilterRegistrations() {
		checkShutdown();

		return filterRegistrations;
	}

	public String getFullContextPath() {
		List<String> endpoints = httpServiceRuntime.getHttpServiceEndpoints();

		if (endpoints.isEmpty()) {
			return proxyContext.getServletPath().concat(contextPath);
		}

		String defaultEndpoint = endpoints.get(0);

		if ((defaultEndpoint.length() > 0) && defaultEndpoint.endsWith("/")) {
			defaultEndpoint = defaultEndpoint.substring(
				0, defaultEndpoint.length() - 1);
		}

		return defaultEndpoint + contextPath;
	}

	public HttpServiceRuntimeImpl getHttpServiceRuntime() {
		checkShutdown();

		return httpServiceRuntime;
	}

	public Map<String, String> getInitParams() {
		return initParams;
	}

	public Set<ListenerRegistration> getListenerRegistrations() {
		checkShutdown();

		return listenerRegistrations;
	}

	public ProxyContext getProxyContext() {
		checkShutdown();

		return proxyContext;
	}

	public long getServiceId() {
		checkShutdown();

		return contextServiceId;
	}

	public synchronized ServletContextDTO getServletContextDTO(){
		checkShutdown();

		ServletContextDTO servletContextDTO = new ServletContextDTO();

		ServletContext servletContext = getProxyContext().getServletContext();

		servletContextDTO.attributes = getAttributes(servletContext);
		servletContextDTO.contextName = servletContext.getServletContextName();
		servletContextDTO.contextPath = servletContext.getContextPath();
		servletContextDTO.initParams = initParams;
		servletContextDTO.name = getContextName();
		servletContextDTO.serviceId = getServiceId();

		// TODO
		servletContextDTO.errorPageDTOs = new ErrorPageDTO[0];

		collectEndpointDTOs(servletContextDTO);
		collectFilterDTOs(servletContextDTO);
		collectListenerDTOs(servletContextDTO);

		return servletContextDTO;
	}

	public boolean matches(ServiceReference<?> whiteBoardService) {
		String contextSelector = (String) whiteBoardService.getProperty(
			HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_SELECT);
		if (contextSelector == null) {
			contextSelector = httpServiceRuntime.getDefaultContextSelectFilter(whiteBoardService);
			if (contextSelector == null) {
				contextSelector = "(" + //$NON-NLS-1$
					HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_NAME + "=" //$NON-NLS-1$
					+ HttpWhiteboardConstants.HTTP_WHITEBOARD_DEFAULT_CONTEXT_NAME + ")"; //$NON-NLS-1$
			}
		}

		if (!contextSelector.startsWith(Const.OPEN_PAREN)) {
			contextSelector = Const.OPEN_PAREN +
				HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_NAME +
					Const.EQUAL + contextSelector + Const.CLOSE_PAREN;
		}

		org.osgi.framework.Filter targetFilter;

		try {
			targetFilter = FrameworkUtil.createFilter(contextSelector);
		}
		catch (InvalidSyntaxException ise) {
			throw new IllegalArgumentException(ise);
		}

		return matches(targetFilter);
	}

	public boolean matches(org.osgi.framework.Filter targetFilter) {
		return targetFilter.matches(attributes);
	}

	@Override
	public String toString() {
		return getClass().getSimpleName() + '[' + getContextName() + ']';
	}

	private void addEnpointRegistrationsToRequestInfo(
		EndpointRegistration<?> endpointRegistration,
		RequestInfoDTO requestInfoDTO) {

		if (requestInfoDTO == null) {
			return;
		}

		requestInfoDTO.servletContextId = getServiceId();

		if (endpointRegistration instanceof ResourceRegistration) {
			requestInfoDTO.resourceDTO =
				(ResourceDTO)endpointRegistration.getD();
		}
		else {
			requestInfoDTO.servletDTO =
				(ServletDTO)endpointRegistration.getD();
		}
	}

	private void addFilterRegistrationsToRequestInfo(
		List<FilterRegistration> matchedFilterRegistrations,
		RequestInfoDTO requestInfoDTO) {

		if (requestInfoDTO == null) {
			return;
		}

		FilterDTO[] filterDTOs =
			new FilterDTO[matchedFilterRegistrations.size()];

		for (int i = 0; i < filterDTOs.length ; i++) {
			FilterRegistration filterRegistration =
				matchedFilterRegistrations.get(i);

			filterDTOs[i] = filterRegistration.getD();
		}

		requestInfoDTO.filterDTOs = filterDTOs;
	}

	private String[] asStringArray(
		List<Class<? extends EventListener>> clazzes) {

		String[] classesArray = new String[clazzes.size()];

		for (int i = 0; i < classesArray.length; i++) {
			classesArray[i] = clazzes.get(i).getName();
		}

		Arrays.sort(classesArray);

		return classesArray;
	}

	private String[] checkDispatcher(String[] dispatcher) {
		if ((dispatcher == null) || (dispatcher.length == 0)) {
			return DISPATCHER;
		}

		for (String type : dispatcher) {
			try {
				Const.Dispatcher.valueOf(type);
			}
			catch (IllegalArgumentException iae) {
				throw new IllegalArgumentException(
					"Invalid dispatcher '" + type + "'", iae);
			}
		}

		Arrays.sort(dispatcher);

		return dispatcher;
	}

	public static void checkPattern(String pattern) {
		if (pattern == null) {
			throw new IllegalArgumentException("Pattern cannot be null");
		}

		if (pattern.indexOf("*.") == 0) { //$NON-NLS-1$
			return;
		}

		if (!pattern.startsWith(Const.SLASH) ||
			(pattern.endsWith(Const.SLASH) && !pattern.equals(Const.SLASH))) {

			throw new IllegalArgumentException(
				"Invalid pattern '" + pattern + "'");
		}
	}

	private void checkPrefix(String prefix) {
		if (prefix == null) {
			throw new IllegalArgumentException("Prefix cannot be null");
		}

		if (prefix.endsWith(Const.SLASH) && !prefix.equals(Const.SLASH)) {
			throw new IllegalArgumentException("Invalid prefix '" + prefix + "'");
		}
	}

	private void checkShutdown() {
		if (shutdown) {
			throw new IllegalStateException(
				"Context is already shutdown"); //$NON-NLS-1$
		}
	}

	private ServletContext createServletContext(
		Bundle curBundle, ServletContextHelper curServletContextHelper) {

		ServletContextAdaptor adaptor = new ServletContextAdaptor(
			this, curBundle, curServletContextHelper, eventListeners,
			AccessController.getContext());

		return adaptor.createServletContext();
	}

	private void collectEndpointDTOs(
		ServletContextDTO servletContextDTO) {

		List<ResourceDTO> resourceDTOs = new ArrayList<ResourceDTO>();
		List<ServletDTO> servletDTOs = new ArrayList<ServletDTO>();

		for (EndpointRegistration<?> endpointRegistration : endpointRegistrations) {
			if (endpointRegistration instanceof ResourceRegistration) {
				resourceDTOs.add((ResourceDTO)endpointRegistration.getD());
			}
			else {
				servletDTOs.add((ServletDTO)endpointRegistration.getD());
			}
		}

		servletContextDTO.resourceDTOs = resourceDTOs.toArray(
			new ResourceDTO[resourceDTOs.size()]);
		servletContextDTO.servletDTOs = servletDTOs.toArray(
			new ServletDTO[servletDTOs.size()]);
	}

	private void collectFilterDTOs(
		ServletContextDTO servletContextDTO) {

		List<FilterDTO> filterDTOs = new ArrayList<FilterDTO>();

		for (FilterRegistration filterRegistration : filterRegistrations) {
			filterDTOs.add(filterRegistration.getD());
		}

		servletContextDTO.filterDTOs = filterDTOs.toArray(
			new FilterDTO[filterDTOs.size()]);
	}

	private void collectListenerDTOs(
		ServletContextDTO servletContextDTO) {

		List<ListenerDTO> listenerDTOs = new ArrayList<ListenerDTO>();

		for (ListenerRegistration listenerRegistration : listenerRegistrations) {
			listenerDTOs.add(listenerRegistration.getD());
		}

		servletContextDTO.listenerDTOs = listenerDTOs.toArray(
			new ListenerDTO[listenerDTOs.size()]);
	}

	private Map<String, Object> getAttributes(ServletContext servletContext) {
		Map<String, Object> map = new HashMap<String, Object>();

		for (Enumeration<String> names = servletContext.getAttributeNames();
				names.hasMoreElements();) {

			String name = names.nextElement();

			map.put(name, servletContext.getAttribute(name));
		}

		return Collections.unmodifiableMap(map);
	}

	private List<Class<? extends EventListener>> getListenerClasses(
		EventListener eventListener) {

		List<Class<? extends EventListener>> classes =
			new ArrayList<Class<? extends EventListener>>();

		if (ServletContextListener.class.isInstance(eventListener)) {
			classes.add(ServletContextListener.class);
		}
		if (ServletContextAttributeListener.class.isInstance(eventListener)) {
			classes.add(ServletContextAttributeListener.class);
		}
		if (ServletRequestListener.class.isInstance(eventListener)) {
			classes.add(ServletRequestListener.class);
		}
		if (ServletRequestAttributeListener.class.isInstance(eventListener)) {
			classes.add(ServletRequestAttributeListener.class);
		}
		if (HttpSessionListener.class.isInstance(eventListener)) {
			classes.add(HttpSessionListener.class);
		}
		if (HttpSessionAttributeListener.class.isInstance(eventListener)) {
			classes.add(HttpSessionAttributeListener.class);
		}

		return classes;
	}

	private ServletContextHelper getServletContextHelper(Bundle curBundle) {
		BundleContext context = curBundle.getBundleContext();
		return context.getService(servletContextHelperRef);
	}

	public void ungetServletContextHelper(Bundle curBundle) {
		BundleContext context = curBundle.getBundleContext();
		try {
			context.ungetService(servletContextHelperRef);
		} catch (IllegalStateException e) {
			// this can happen if the whiteboard bundle is in the process of stopping
			// and the framework is in the middle of auto-unregistering any services
			// the bundle forgot to unregister on stop
		}
	}

	private String[] sort(String[] values) {
		if (values == null) {
			return null;
		}

		Arrays.sort(values);

		return values;
	}

	private static final String[] DISPATCHER =
		new String[] {Const.Dispatcher.REQUEST.toString()};

	private Map<String, Object> attributes;
	private Map<String, String> initParams;
	private final BundleContext trackingContext;
	private final BundleContext consumingContext;
	private final String contextName;
	private final String contextPath;
	private final long contextServiceId;
	private final Set<EndpointRegistration<?>> endpointRegistrations = new ConcurrentSkipListSet<EndpointRegistration<?>>();
	private final EventListeners eventListeners = new EventListeners();
	private final Set<FilterRegistration> filterRegistrations = new HashSet<FilterRegistration>();

	private final HttpServiceRuntimeImpl httpServiceRuntime;
	private final Set<ListenerRegistration> listenerRegistrations = new HashSet<ListenerRegistration>();
	private final ProxyContext proxyContext;
	private final ServiceReference<ServletContextHelper> servletContextHelperRef;
	private boolean shutdown;

	private final ServiceTracker<Filter, AtomicReference<FilterRegistration>> filterServiceTracker;
	private final ServiceTracker<EventListener, AtomicReference<ListenerRegistration>> listenerServiceTracker;
	private final ServiceTracker<Servlet, AtomicReference<ServletRegistration>> servletServiceTracker;
	private final ServiceTracker<Servlet, AtomicReference<ResourceRegistration>> resourceServiceTracker;
}