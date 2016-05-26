//Copyright (C) 2011-2013 Paul Done . All rights reserved.
//This file is part of the HostMachineStats software distribution. Refer to 
//the file LICENSE in the root of the HostMachineStats distribution.
//THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
//AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE 
//IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE 
//ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDERS OR CONTRIBUTORS BE 
//LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR 
//CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF 
//SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS 
//INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN 
//CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
//ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE 
//POSSIBILITY OF SUCH DAMAGE.
package wlsoabpmstats.mbeans;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

import javax.management.MBeanRegistration;
import javax.management.MBeanServer;
import javax.management.MBeanServerConnection;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;
import javax.naming.InitialContext;
import javax.naming.NamingException;

import wlsoabpmstats.util.AppLog;

import com.bea.wli.monitoring.ServiceDomainMBean;

/**
 * Implementation of the MBean exposing O.S/machine statistics for the machine
 * hosting this WebLogic Server instances. Provides read-only attributes for 
 * useful CPU, Memory and Network related usages statistics.Use SIGAR JNI/C 
 * libraries under the covers (http://support.hyperic.com/display/SIGAR/Home) 
 * to retrieve specific statistics from host operating system.
 *  
 * @see javax.management.MXBean
 */
public class WLSoaBpmStats implements WLSoaBpmStatsMXBean, MBeanRegistration {
	
	// Constants
	private static final String WL_SOA_BPM_APP_VERSION = "0.0.1";
	
	
	protected final static String JNDI_ROOT = "/jndi/";
	private final static String[] LOCAL_SERVER_RUNTIME_MBEAN_JNDI_LOOKUPS = {"java:comp/env/jmx/runtime", "java:comp/jmx/runtime"};
	private static volatile MBeanServerConnection cachedLocalConn = null;
	
	// Members 
	private ServiceDomainMBean serviceDomainMBean = null;
	
	//private static final ObjectName domainRuntimeServiceMBean;
	private static final ObjectName serverRuntimeServiceMBean;
	
	/*
	static {
		try {
			domainRuntimeServiceMBean = new ObjectName("com.bea:Name=DomainRuntimeService,Type=weblogic.management.mbeanservers.domainruntime.DomainRuntimeServiceMBean");
		} catch (MalformedObjectNameException e) {
			throw new AssertionError(e.toString());
		}
	}
	*/
	
	static {
		try {
			serverRuntimeServiceMBean = new ObjectName("com.bea:Name=RuntimeService,Type=weblogic.management.mbeanservers.runtime.RuntimeServiceMBean");
		} catch (MalformedObjectNameException e) {
			throw new AssertionError(e.toString());
		}
	}
	
	//public final static String DOMAIN_CONFIGURATION = "DomainConfiguration";
	
	// Constants
	private static final String DOMAIN_RUNTIME_SERVICE_NAME = "weblogic.management.mbeanservers.domainruntime";
	private static final String WEBLOGIC_PROVIDER_PACKAGES = "weblogic.management.remote";
	private static final String WEBLOGIC_INSECURE_REMOTE_PROTOCOL = "t3";
	private static final String WEBLOGIC_SECURE_REMOTE_PROTOCOL = "t3s";
	
	public static final String DEFAULT_WKMGR_NAME = "weblogic.kernel.Default";
	public final static String SERVER_RUNTIME = "ServerRuntime";
	public final static String ADMIN_SERVER_HOSTNAME = "AdminServerHost";
	public final static String ADMIN_SERVER_PORT = "AdminServerListenPort";
	public final static String ADMIN_SERVER_NAME = "AdminServerName";
	public final static String IS_ADMIN_SERVER_PORT_SECURED = "AdminServerListenPortSecure";
	public final static String WORK_MANAGER_RUNTIMES = "WorkManagerRuntimes";
	public final static String NAME = "Name";

	/**
	 * 
	 */
	private boolean init(String serviceName) {
		
		try {
			MBeanServerConnection localConn = getCachedLocalConn();
			ObjectName serverRuntime = (ObjectName) localConn.getAttribute(serverRuntimeServiceMBean, SERVER_RUNTIME);
			boolean isSecure = ((Boolean) localConn.getAttribute(serverRuntime, IS_ADMIN_SERVER_PORT_SECURED)).booleanValue();
			String protocol = isSecure ? WEBLOGIC_SECURE_REMOTE_PROTOCOL: WEBLOGIC_INSECURE_REMOTE_PROTOCOL;
			String host = (String) localConn.getAttribute(serverRuntime, ADMIN_SERVER_HOSTNAME);
			int port = ((Integer) localConn.getAttribute(serverRuntime, ADMIN_SERVER_PORT)).intValue();
			jmxConnector = JMXConnectorFactory.connect(new JMXServiceURL(protocol, host, port, JNDI_ROOT + serviceName), getJMXContextProps());
			conn = jmxConnector.getMBeanServerConnection();
			
			return true;
		} catch (Exception ex) {
			AppLog.getLogger().error("Error during init of jmxConnector object - The message is [" + ex.getMessage() + "]");
			
			jmxConnector = null;
			conn = null;
			serviceDomainMBean = null;
			return false;
		}
	}
	
	/**
	 * Populate a JNDI context property file with the minimum properties 
	 * required to access a WebLogic MBean server tree
	 * 
	 * @return WebLogic properties file
	 */
	protected Map<String, String> getJMXContextProps() {
		Map<String, String> props = new HashMap<String, String>();
		props.put(JMXConnectorFactory.PROTOCOL_PROVIDER_PACKAGES, WEBLOGIC_PROVIDER_PACKAGES);
		return props;
	}
	
	/**
	 * Returns the current MBeanServer connection to overriding classes.
	 * 
	 * @return The current JMX Mbean server connection
	 */
	protected MBeanServerConnection getConn() {
		return conn;
	}
	
	// Members
	private JMXConnector jmxConnector;
	private MBeanServerConnection conn;
	
	/**
	 * 
	 * @return
	 * @throws NamingException
	 */
	private MBeanServerConnection getCachedLocalConn() throws NamingException {
		if (cachedLocalConn == null) {		
			synchronized (WLSoaBpmStats.class) {
				if (cachedLocalConn == null) {
					InitialContext ctx = null;
					
					try {
						ctx = new InitialContext();
						
						for (String jndiLookup : LOCAL_SERVER_RUNTIME_MBEAN_JNDI_LOOKUPS) {
							try {
								cachedLocalConn = (MBeanServer) ctx.lookup(jndiLookup);
								
								if (cachedLocalConn == null) {
									AppLog.getLogger().debug("Unable to locate local server runtime mbean using jndi lookup of: " + jndiLookup);
								} else {
									AppLog.getLogger().debug("Successfully located local server runtime mbean using jndi lookup of: " + jndiLookup);
									break;
								}
							} catch (Exception e) {
								AppLog.getLogger().error("Error attempting to locate local server runtime mbean using jndi lookup of: " + jndiLookup + "  (" + e + ")");
							}
						}
					} finally {
						if (ctx != null) {
							try { ctx.close(); } catch (Exception e) {}
						}
					}
				}
			}
		}
		return cachedLocalConn;
	}
	
	/**
	 * 
	 * @return
	 */
	/*
	private ObjectName getServerRuntime() throws Exception {
		
		ObjectName serverRuntime = (ObjectName)getConn().getAttribute(serverRuntimeServiceMBean, SERVER_RUNTIME);
		if(serverRuntime != null) {
			AppAppLog.getLogger().getLogger().notice("Found the ServerRuntime");
			return serverRuntime;
		} else {
			AppAppLog.getLogger().getLogger().error("Didn't find the ServerRuntime ...");
			return null;
		}
	}
	*/
	
	/**
	 * 
	 */
	private boolean initServiceDomainMBean() {
		
		// -----------------------------------------------------------------------
		try {
			
			/*
			// --------------------------------------------------
			// Is working but DH is a strong dependency ...
			DomainRuntimeServiceMBeanConnection conn = new DomainRuntimeServiceMBeanConnection();
			serviceDomainMBean = getServiceDomainMBean(conn);
			if(serviceDomainMBean != null) {
				AppAppLog.getLogger().getLogger().debug("serviceDomainMBean is properly configured");
				return true;
			} else {
				AppAppLog.getLogger().getLogger().error("Unable to set serviceDomainMBean ...");
				return false;
			}
			// --------------------------------------------------
			*/
			
			if(init(DOMAIN_RUNTIME_SERVICE_NAME)) {
				
				serviceDomainMBean = getServiceDomainMBean(getConn());
				if(serviceDomainMBean != null) {
					AppLog.getLogger().debug("serviceDomainMBean is properly configured");
					return true;
				}
				else {
					AppLog.getLogger().error("Unable to set serviceDomainMBean");
					return false;
				}
			}
			else {
				AppLog.getLogger().error("Unable to set serviceDomainMBean - The execution of init() method failed");
				return false;
			}
		} catch (Exception ex) {
			AppLog.getLogger().error("Unable to set serviceDomainMBean - The error message is [" + ex.getMessage());
			return false;
		}
		// -----------------------------------------------------------------------
				
	}
			
	/**
	 * Main constructor 
	 */
	public WLSoaBpmStats() {
		
		// Check if the MBean is instantiated each time of kept in memory for better performance
		// -> Need to know how the data should be retrieved
		if(!initServiceDomainMBean()) {
			throw new IllegalStateException ("Unable to create WLSoaBpmStats object");
		}
	}
	
	/**
	 * Pre-register event handler - returns MBean name.
	 * 
	 * @return name
	 */
	public ObjectName preRegister(MBeanServer server, ObjectName name) throws Exception {
		return name;
	}

	/**
	 * Post-register event handler - logs that started.
	 * 
	 * @param registrationDone Indicates if registration was completed
	 */
	public void postRegister(Boolean registrationDone) {
		AppLog.getLogger().notice("WlSoaBpmStats MBean initialised");
	}

	/**
	 * Pre-deregister event handler - does nothing
	 * 
	 * @throws Exception Indicates problem is post registration
	 */
	public void preDeregister() throws Exception {
	}

	/**
	 * Post-deregister event handler - logs that stopped
	 */
	public void postDeregister() {
		AppLog.getLogger().notice("WlSoaBpmStats MBean destroyed");
	}

	/**
	 * The version of the WLSoaBpmStats MBean. 
	 * Format: "x.x.x". Example: "0.1.0".
	 * 
	 * @return The version of WLSoaBpmStats MBean
	 */
	public String getMBeanVersion() {
		return WL_SOA_BPM_APP_VERSION;
	}
	
	
	/**
	 * Gets an instance of ServiceDomainMBean from the weblogic server.
	 * 
	 * @param conn
	 * @return
	 */
	private ServiceDomainMBean getServiceDomainMBean(MBeanServerConnection conn) {	
    	InvocationHandler handler = new ServiceDomainMBeanInvocationHandler(conn);
		Object proxy = Proxy.newProxyInstance(ServiceDomainMBean.class.getClassLoader(), new Class[] { ServiceDomainMBean.class }, handler);
		return (ServiceDomainMBean) proxy;
	}
}