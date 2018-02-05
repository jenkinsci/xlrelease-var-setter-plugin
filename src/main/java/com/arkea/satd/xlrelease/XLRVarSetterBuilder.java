package com.arkea.satd.xlrelease;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.util.List;
import java.util.regex.Pattern;

import javax.servlet.ServletException;

import org.apache.commons.io.IOUtils;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.AuthCache;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.auth.BasicScheme;
import org.apache.http.impl.client.BasicAuthCache;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.json.JSONArray;
import org.json.JSONObject;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.ProxyConfiguration;
import hudson.model.AbstractProject;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.FormValidation;
import jenkins.model.Jenkins;
import jenkins.tasks.SimpleBuildStep;

/**
 * Sample {@link Builder}.
 *
 * <p>
 * When the user configures the project and enables this builder,
 * {@link DescriptorImpl#newInstance(StaplerRequest)} is invoked
 * and a new {@link XLRVarSetterBuilder} is created. The created
 * instance is persisted to the project configuration XML by using
 * XStream, so this allows you to use instance fields (like {@link #name})
 * to remember the configuration.
 *
 * <p>
 * When a build is performed, the {@link #perform} method will be invoked. 
 *
 * @author Kohsuke Kawaguchi
 */
public class XLRVarSetterBuilder extends Builder implements SimpleBuildStep {

    private final String XLR_releaseId;
    private final String XLR_varName;
    private final String JKS_varName;
    private boolean debug;
    
    private ProxyConfiguration proxyConfiguration;
    private boolean hasProxy;
    
    // Fields in config.jelly must match the parameter names in the "DataBoundConstructor"
    @DataBoundConstructor
    public XLRVarSetterBuilder(
    		String XLR_releaseId,
    		String XLR_varName, 
    		String JKS_varName,
    		boolean debug) {
        this.XLR_releaseId = XLR_releaseId;
        this.XLR_varName = XLR_varName;
        this.JKS_varName = JKS_varName;
        this.debug = debug;
    }

    /**
     * We'll use this from the {@code config.jelly}.
     */

    public String getXLR_releaseId() {
		return XLR_releaseId;
	}

	public String getXLR_varName() {
		return XLR_varName;
	}

	public String getJKS_varName() {
		return JKS_varName;
	}

	public boolean isDebug() {
		return debug;
	}

	@Override
    public void perform(Run<?,?> build, FilePath workspace, Launcher launcher, TaskListener listener) throws IOException, InterruptedException {

		// Get local variables
		EnvVars envVars = build.getEnvironment(listener);
		
		// Get Global & Config Variables
		String xlrReleaseId = envVars.get("XLR_releaseId");
		String JKS_varValue = envVars.get(getJKS_varName().toString());
		String xlrHostName = getDescriptor().getXLR_hostName();
		int xlrHostPort = getDescriptor().getXLR_hostPort(); 
		String xlrUser = getDescriptor().getXLR_user();
		String xlrPwd = getDescriptor().getXLR_pwd();
		
		// Log 
		if (isDebug()) { 
			listener.getLogger().println("[XLRVarSetter] - XL-Release host name = " + xlrHostName);
			listener.getLogger().println("[XLRVarSetter] - XL-Release host port = " + xlrHostPort);
			listener.getLogger().println("[XLRVarSetter] - XL-Release user = " + getDescriptor().getXLR_user());
			listener.getLogger().println("[XLRVarSetter] - XL-Release release Id = "  + xlrReleaseId);		
			listener.getLogger().println("[XLRVarSetter] - XL-Release variable name = " + getXLR_varName());
			listener.getLogger().println("[XLRVarSetter] - Value of " + getJKS_varName() + " = " + JKS_varValue);
		}

		// Prepare Http Client / Host for XL-Release API requests
		CloseableHttpClient httpclient = HttpClientBuilder.create().build();
		HttpHost xlrHost = new HttpHost(xlrHostName,xlrHostPort);
		
		// Create the url to get the release variables list
		String xlrVarListRequest = "/api/v1/releases/" + xlrReleaseId + "/variables";
		
		// Submit the request and create the response
		CloseableHttpResponse getXlrVars= createHttpResponse(httpclient,xlrHost,xlrUser,xlrPwd,xlrVarListRequest,"GET",new JSONObject(),listener.getLogger());
		
		// Read the result and close the response
		HttpEntity resEntity = getXlrVars.getEntity();
		InputStream is = resEntity.getContent();
		String responseBody = IOUtils.toString(is);
		getXlrVars.close();
		
		// Create a JsonArray from the response
		JSONArray vars = new JSONArray(responseBody);
		
		//log
		if (isDebug()) {
			listener.getLogger().println("[XLRVarSetter] - all release variables :" + vars.toString(1)); 
			}
		
		// Find and extract var with key defined by XLR_varName
		JSONObject varToChange = new JSONObject();
		for (int i=0 ; i < vars.length() ; i++) {
			JSONObject var = vars.getJSONObject(i);
			String varKey = var.getString("key");
			if (getXLR_varName().equals(varKey)) {
				varToChange = var;
			}
		}
		
		// Variable founded
		if (0 < varToChange.length()) {
			
			// Get the Variable Id
			String varId = varToChange.getString("id");
			
			// Create a new Json Object by copying the found one
			JSONObject updatedVar = varToChange;
			
			// Add / Update the value
			updatedVar.put("value", JKS_varValue);
			
			// Create the Variable Url
			String xlrVarUpdateRequest = "/api/v1/releases/" + varId;
			
			listener.getLogger().println("[XLRVarSetter] - XL-Release update url : http://" + xlrHostName + ":" + xlrHostPort + xlrVarUpdateRequest);
			
			// Submit the request and create the response
			CloseableHttpResponse postXlrVar= createHttpResponse(httpclient,xlrHost,xlrUser,xlrPwd,xlrVarUpdateRequest,"PUT",updatedVar,listener.getLogger());
			
			HttpEntity resPostEntity = postXlrVar.getEntity();
			InputStream isPost = resPostEntity.getContent();
			String responsePostBody = IOUtils.toString(isPost);
			
			// Close the response
			postXlrVar.close();
			
			listener.getLogger().println("[XLRVarSetter] - XL-Release update data : " + updatedVar.toString(1));			
			listener.getLogger().println("[XLRVarSetter] - XL-Release update response : " + responsePostBody);
			
		} else {
			// Variable not found in the list
			try {
				throw new IOException("[XLRVarSetter] - ERROR - Variable " + getXLR_varName() + " not found in release id : " + xlrReleaseId);
			} finally {
				httpclient.close();
			}
		}
		
		// Close http client
		httpclient.close();
    }

    // Overridden for better type safety.
    // If your plugin doesn't really define any property on Descriptor,
    // you don't have to do this.
    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl)super.getDescriptor();
    }

    /**
     * Descriptor for {@link XLRVarSetterBuilder}. Used as a singleton.
     * The class is marked as public so that it can be accessed from views.
     *
     * <p>
     * See {@code src/main/resources/hudson/plugins/hello_world/HelloWorldBuilder/*.jelly}
     * for the actual HTML fragment for the configuration screen.
     */
    @Extension // This indicates to Jenkins that this is an implementation of an extension point.
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {
        /**
         * To persist global configuration information,
         * simply store it in a field and call save().
         *
         * <p>
         * If you don't want fields to be persisted, use {@code transient}.
         */
        
        private String XLR_hostName;
        private int    XLR_hostPort;
        private String XLR_user;
        private String XLR_pwd;
        
        /**
         * In order to load the persisted global configuration, you have to 
         * call load() in the constructor.
         */
        public DescriptorImpl() {
            load();
        }

        /**
         * Performs on-the-fly validation of the form field 'name'.
         *
         * @param value
         *      This parameter receives the value that the user has typed.
         * @return
         *      Indicates the outcome of the validation. This is sent to the browser.
         *      <p>
         *      Note that returning {@link FormValidation#error(String)} does not
         *      prevent the form from being saved. It just means that a message
         *      will be displayed to the user. 
         */
        public FormValidation doTestConnection(
        		@QueryParameter("XLR_hostName") final String XLR_hostName,
        		@QueryParameter("XLR_hostPort") final String XLR_hostPort,
        		@QueryParameter("XLR_user") final String XLR_user,
        		@QueryParameter("XLR_pwd") final String XLR_pwd)
                throws IOException, ServletException {
            
        	// Basic control, all fields must be filled
        	if (XLR_hostName.length() == 0 || XLR_hostPort.length() == 0 || XLR_user.length() == 0 || XLR_pwd.length() == 0)
        		return FormValidation.error("Missing parameters");
            
        	// Creation et initialisation of the http test connection 
        	CloseableHttpClient httpclientTest = HttpClientBuilder.create().build();
    		HttpHost xlrHost = new HttpHost(XLR_hostName,Integer.parseInt(XLR_hostPort));
    		
    		// test url (XL-Release global variables)
    		String testRequest = "/api/v1/config/Configuration/variables/global";
        	
    		ProxyConfiguration proxyConfiguration;
        	boolean hasProxy;
        	
        	Jenkins jenkins = Jenkins.getInstance();
        	
        	if (jenkins != null && jenkins.proxy != null) {
        		proxyConfiguration =  jenkins.proxy;
        		hasProxy = true;
        	} else {
        		// Define a proxy configuration to pass findbugs tests
        		proxyConfiguration = new ProxyConfiguration("localhost", 3128);
        		hasProxy = false;
        	}
        	
    		// Credentials and Context for XL-Release server
            CredentialsProvider targetProvider = new BasicCredentialsProvider();
    		
    		UsernamePasswordCredentials targetCredentials = new UsernamePasswordCredentials(
    				XLR_user,
    				XLR_pwd);
    		targetProvider.setCredentials(AuthScope.ANY, targetCredentials);
    		
    		AuthCache authCache = new BasicAuthCache();
    		authCache.put(xlrHost, new BasicScheme());
    		
    		final HttpClientContext context = HttpClientContext.create();
    		context.setCredentialsProvider(targetProvider);
    		context.setAuthCache(authCache);
    		
            // ProxyConfig might have noproxy-exception for certain hosts
            boolean useProxy = true;
            String matchedPattern = null; // to log properly
            
            if (hasProxy) {
            	
                List<Pattern> noProxyHostPatterns = proxyConfiguration.getNoProxyHostPatterns();
                 for (int i = 0; i < noProxyHostPatterns.size(); i++) {
                    Pattern noproxypattern = noProxyHostPatterns.get(i);
                    if (noproxypattern.matcher(xlrHost.getHostName()).matches()) {
                        useProxy = false;
                        matchedPattern = noproxypattern.toString();
                    };
                }
            }
    		
            // Prepare http Response
    		CloseableHttpResponse response = null;
    		
            if (hasProxy &&
                    useProxy ) {

                HttpHost proxy;
                
                // Check if Jenkins Proxy need authentication
//              boolean proxyIsAuthentified = null != proxyConfiguration.getUserName();

                proxy = new HttpHost(proxyConfiguration.name, proxyConfiguration.port);
//               	if (proxyIsAuthentified) {
//            		// TODO - Add credentials for proxy
//            		String proxyUserName = proxyConfiguration.getUserName();
//            		String proxyPassword = proxyConfiguration.getPassword();
//            	}
            	
            	// Set proxy settings to the config
            	RequestConfig config = RequestConfig.custom().setProxy(proxy).build();
            	
            	// Add the config to the request
            	HttpGet request = new HttpGet("http://" + xlrHost.getHostName() + ":" + xlrHost.getPort() + testRequest);
            	request.setConfig(config);
            	
            	// Execute the request and create the response
                response = httpclientTest.execute(request, context);
            	
                // Check the response status 
                if (response.getStatusLine().getStatusCode() != 200) {
                	response.close();
                	return FormValidation.error("Connection error");
                }

                // Close the response
                response.close();
            }
            
            // Close the http client
            httpclientTest.close();
            
            // Test OK
        	return FormValidation.ok("Connection OK");
        	
        }

        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            // Indicates that this builder can be used with all kinds of project types 
            return true;
        }

        /**
         * This human readable name is used in the configuration screen.
         */
        public String getDisplayName() {
            return "XL-Release Var Setter";
        }

        @Override
        public boolean configure(StaplerRequest req, net.sf.json.JSONObject formData) throws FormException {
            // To persist global configuration information,
            // set that to properties and call save().
            
            XLR_hostName = formData.getString("XLR_hostName");
            XLR_hostPort = formData.getInt("XLR_hostPort");
            XLR_user = formData.getString("XLR_user");
            XLR_pwd = formData.getString("XLR_pwd");
            
            // ^Can also use req.bindJSON(this, formData);
            //  (easier when there are many fields; need set* methods for this, like setUseFrench)
            save();
            return super.configure(req,formData);
        }

        /**
         * This method returns true if the global configuration says we should speak French.
         *
         * The method name is bit awkward because global.jelly calls this method to determine
         * the initial state of the checkbox by the naming convention.
         */
        
        public String getXLR_hostName() {
			return XLR_hostName;
		}

		public int getXLR_hostPort() {
			return XLR_hostPort;
		}

		public String getXLR_user() {
        	return XLR_user;
        }
        
        public String getXLR_pwd() {
        	return XLR_pwd;
        }
    }
    
    // create an httpclient CloseableHttpResponse
    private CloseableHttpResponse createHttpResponse(
    		CloseableHttpClient httpclient,
    		HttpHost targetHost, 
    		String targetUser,
    		String targetPwd,
    		String targetRequest,
    		String targetMethod,
    		JSONObject inputData,
    		PrintStream logger
    		) throws IOException {
    	
    	Jenkins jenkins = Jenkins.getInstance();
    	if (null != jenkins) {
    		if (null != jenkins.proxy) {
    			proxyConfiguration =  jenkins.proxy;
    			hasProxy = true;
    		}
    	} else {
    		// Define a proxy configuration to pass findbugs tests
    		proxyConfiguration = new ProxyConfiguration("localhost", 3128);
    		hasProxy = false;
    	}
    	
        if (isDebug()) {
        	logger.println("[XLRVarSetter] - Jenkins Proxy Settings : " + hasProxy);
        }
        
        // Credentials and Context for XL-Release server
        CredentialsProvider targetProvider = new BasicCredentialsProvider();
		
		UsernamePasswordCredentials targetCredentials = new UsernamePasswordCredentials(
				targetUser,
				targetPwd);
		targetProvider.setCredentials(AuthScope.ANY, targetCredentials);
		
		AuthCache authCache = new BasicAuthCache();
		authCache.put(targetHost, new BasicScheme());
        
		// Add AuthCache to the execution context
		final HttpClientContext context = HttpClientContext.create();
		context.setCredentialsProvider(targetProvider);
		context.setAuthCache(authCache);
		
        // ProxyConfig might have noproxy-exception for certain hosts
        boolean useProxy = true;
        String matchedPattern = null; // to log properly
        
        if (hasProxy) {
        	
            List<Pattern> noProxyHostPatterns = proxyConfiguration.getNoProxyHostPatterns();
             for (int i = 0; i < noProxyHostPatterns.size(); i++) {
                Pattern noproxypattern = noProxyHostPatterns.get(i);
                if (noproxypattern.matcher(targetHost.getHostName()).matches()) {
                    useProxy = false;
                    matchedPattern = noproxypattern.toString();
                };
            }
        }

     // Prepare http Response
        CloseableHttpResponse response = null;
        
        // Proxy setting, we have a Proxy _and_ the provided URL does not match any no-proxy-override
        if (hasProxy &&
                useProxy ) {

        	if (isDebug()) {
        		logger.println("[XLRVarSetter] - Jenkins Proxy name = " + proxyConfiguration.name);
        		logger.println("[XLRVarSetter] - Jenkins Proxy port = " + proxyConfiguration.port);
        	}
            
            HttpHost proxy;
            
            // Check if Jenkins Proxy need authentication
            boolean proxyIsAuthentified = null != proxyConfiguration.getUserName();
        	
        	if (proxyIsAuthentified) {
        		// TODO - Add credentials for proxy
        		// String proxyUserName = proxyConfiguration.getUserName();
        		// String proxyPassword = proxyConfiguration.getPassword();
        		// ...
        		if (isDebug()) {
        			logger.println("[XLRVarSetter] - Jenkins Proxy need Auth (Not implemented yet)");
        		}
        		proxy = new HttpHost(proxyConfiguration.name, proxyConfiguration.port);
        		
        	} else {
        		if (isDebug()) {
        			logger.println("[XLRVarSetter] - Jenkins Proxy doesn't need Auth");
        		}
        		proxy = new HttpHost(proxyConfiguration.name, proxyConfiguration.port);
        		
        	}
                
            // Set proxy settings to the config
        	RequestConfig config = RequestConfig.custom().setProxy(proxy).build();
        	
        	if ("GET".equals(targetMethod)) {
        	
        		// Add the config to the request
        		HttpGet request = new HttpGet("http://" + targetHost.getHostName() + ":" + targetHost.getPort() + targetRequest);
        		request.setConfig(config);
        		
        		// Execute the request and create the response
            	response = httpclient.execute(request, context);
        	}
        	
        	if ("PUT".equals(targetMethod)) {
        		// Add the config to the request
        		HttpPut request = new HttpPut("http://" + targetHost.getHostName() + ":" + targetHost.getPort() + targetRequest);
        		request.setConfig(config);
        		
        		// Add the Json Object as data to the request
        		StringEntity requestEntity = new StringEntity(
        				inputData.toString(),
        			    ContentType.APPLICATION_JSON);
        		request.setEntity(requestEntity);
        		
        		// Execute the request and create the response
            	response = httpclient.execute(request, context);
        		
        	}
  
        }
	
	// Small update for those who have Proxy but not use it for XLR access
	if ( hasProxy && !useProxy )
	{                         
            // Set proxy settings to the config
        	RequestConfig config = RequestConfig.custom().RequestConfig.custom()
  								.setConnectTimeout(timeout * 1000)
  								.setConnectionRequestTimeout(timeout * 1000)
  								.setSocketTimeout(timeout * 1000).build();
        	
        	if ("GET".equals(targetMethod)) {
        	
        		// Add the config to the request
        		HttpGet request = new HttpGet("http://" + targetHost.getHostName() + ":" + targetHost.getPort() + targetRequest);
        		request.setConfig(config);
        		
        		// Execute the request and create the response
            	response = httpclient.execute(request, context);
        	}
        	
        	if ("PUT".equals(targetMethod)) {
        		// Add the config to the request
        		HttpPut request = new HttpPut("http://" + targetHost.getHostName() + ":" + targetHost.getPort() + targetRequest);
        		request.setConfig(config);
        		
        		// Add the Json Object as data to the request
        		StringEntity requestEntity = new StringEntity(
        				inputData.toString(),
        			    ContentType.APPLICATION_JSON);
        		request.setEntity(requestEntity);
        		
        		// Execute the request and create the response
            	response = httpclient.execute(request, context);
        		
        	}
  
        }
        
        return response;
    }
}

