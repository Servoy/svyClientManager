package com.servoy.plugins;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import com.servoy.j2db.plugins.IClientPluginAccess;
import com.servoy.j2db.plugins.IServerAccess;
import com.servoy.j2db.plugins.IServerPlugin;
import com.servoy.j2db.plugins.PluginException;
import com.servoy.j2db.server.shared.IClientInformation;
import com.servoy.j2db.util.Debug;

public class ClientManager implements IServerPlugin {

	private final static String LOG_PREFIX = "CLIENT MANAGER PLUGIN";
	
	private final static int INTERVAL = 15;
	
	private final static int DEFAULT_SHUTDOWN_DELAY = 10;
	
	private final static String MAX_IDLE_TIME = "servoy.ClientManager.MaxIdleTime";
	private final static String IDLE_SHUTDOWN_MESSAGE = "servoy.ClientManager.IdleShutDownMessage";
	private final static String MAX_CLIENTS_PER_USER = "servoy.ClientManager.maxClientsPerUser";
	private final static String MAX_CLIENTS_SHUTDOWN_MESSAGE = "servoy.ClientManager.MaxClientsShutDownMessage";
	
	private IServerAccess server;	

	private int maxIdleTime = 0;
	private String idleShutDownMessage = null;
	private int maxClientsPerUser = 0;
	private String maxClientsShutdownMessage = null;
	private int shutdownMessageDelay = DEFAULT_SHUTDOWN_DELAY;
	
	@Override
	public Properties getProperties() {
		Properties properties = new Properties();
		properties.put(DISPLAY_NAME, "Client Manager");
		return properties;
	}

	@Override
	public void load() throws PluginException {
		log("Service Loaded");
	}

	@Override
	public void unload() throws PluginException {

	}

	@Override
	public Map<String, String> getRequiredPropertyNames() {
		Map<String, String> requiredProperties = new HashMap<>();
		requiredProperties.put(MAX_IDLE_TIME, "The maximum time in minutes before clients are disconnected");
		requiredProperties.put(IDLE_SHUTDOWN_MESSAGE, "A message shown to idle clients before shutdown. Leave blank to show no message");
		requiredProperties.put(MAX_CLIENTS_PER_USER, "The max number of client sessions per logged-in user");
		requiredProperties.put(MAX_CLIENTS_SHUTDOWN_MESSAGE, "A message shown to logged in clients before shutdown because of another session started. Leave blank to show no message");
		return requiredProperties;
	}

	@Override
	public void initialize(IServerAccess server) throws PluginException {
		this.server = server;
		loadProperties();
		startService();
	}

	/**
	 * 
	 */
	private void loadProperties(){
		
		Properties properties = server.getSettings();
		
		//	parse MAX_IDLE_TIME time property
		String value = (String)properties.get(MAX_IDLE_TIME);
		if(value != null){
			try{
				maxIdleTime = Integer.parseInt(value, 10) * 36000;
				log("Max idle time set. Web clients will be shutdown after "+value+" minutes of idle time");
				
				//	parse IDLE_SHUTDOWN_MESSAGE time property
				value = (String)properties.get(IDLE_SHUTDOWN_MESSAGE);
				if(value != null){
					idleShutDownMessage = value;
					log("Idle Shutdown message set");
				} else {
					log("No idle shutdown message specified");
				}
			}catch(Exception e){
				warn("Invalid idle time specified ["+value+"]. Idle sessions will not be closed.");
				error(e.getMessage());
			}
		} else {
			warn("No idle time specified. Idle sessions will not be closed.");
		}
		
		//	max clients per user
		value = (String)properties.get(MAX_CLIENTS_PER_USER);
		if(value != null){
			try {
				maxClientsPerUser = Integer.parseInt(value, 10);
				log("Max clients per user set. Duplicate users will be shutdown when "+maxClientsPerUser+" sessions reached.");
				
				//	parse MAX_CLIENTS_SHUTDOWN_MESSAGE time property
				value = (String)properties.get(MAX_CLIENTS_SHUTDOWN_MESSAGE);
				if(value != null){
					maxClientsShutdownMessage = value;
					log("Max client Shutdown message set");
				} else {
					log("No Max client shutdown message specified");
				}
			} catch(Exception e){
				warn("Invalid max clients specified ["+value+"]. Duplicate user sessions will not be closed.");
				error(e.getMessage());
			}
		} else {
			warn("No max clients per user specified. Idle sessions will not be closed.");
		}
		
//		//	parse and apply SHUTDOWN_MESSAGE_DELAY property
//		value = (String)properties.get(SHUTDOWN_MESSAGE_DELAY);
//		if(value != null){
//			try {
//				shutdownMessageDelay = Integer.parseInt(value, 10) * 1000;
//				log("Shutdown delay set to "+value+" seconds");
//				
//			} catch(Exception e){
//				warn("Invalid shutdown delay specified ["+value+"]. Default value will be used.");
//				error(e.getMessage());
//			}
//		} else {
//			warn("No shutdown message delay specified. Default value will be used.");
//		}
	}
	
	private void startService(){

		Runnable job = new Runnable() {
			public void run() {
				trace("Starting scheduled task...");
				checkClientsIdleTime();
				checkMaxSessions();
			}
		};
		
		//	start service
		log("Service starting...");
		server.getExecutor().scheduleWithFixedDelay(job, 0, INTERVAL, TimeUnit.SECONDS);
		log("Service Started");
	}
	
	private void checkMaxSessions(){
		//	 no setting for max sessions
		if(maxClientsPerUser <= 0){
			return;
		}
		
		//	build session map
		HashMap<String, ArrayList<IClientInformation>> sessionMap = new HashMap<String, ArrayList<IClientInformation>>();
		IClientInformation[] clients = server.getConnectedClients();
		for (int i = 0; i < clients.length; i++) {
			IClientInformation client = clients[0];
			String uid = client.getUserUID();
			ArrayList<IClientInformation> sessions = sessionMap.get(uid);
			if(sessions == null){
				sessions = new ArrayList<IClientInformation>();
				sessionMap.put(uid, sessions);
			}
			sessions.add(client);
		}
		
		//	check session map
		Iterator<String> i = sessionMap.keySet().iterator();
		while(i.hasNext()){
			String uid = i.next();
			ArrayList<IClientInformation> sessions = sessionMap.get(uid);
			int numSessions = sessions.size();
			if(numSessions > maxClientsPerUser){
				warn("User ["+uid+"] has "+numSessions+" sessions. The max is "+maxClientsPerUser+". Sessions will be closed.");
				
				//	sort by login time asc
				Collections.sort(sessions, new Comparator<IClientInformation>() {

					@Override
					public int compare(IClientInformation o1, IClientInformation o2) {
						if(o1.getLoginTime().getTime() < o2.getLoginTime().getTime()){
							return 1;
						}
						if(o1.getLoginTime().getTime() > o2.getLoginTime().getTime()){
							return -1;
						}
						return 0;
					}
				});
				while(sessions.size() > maxClientsPerUser){
					IClientInformation client = sessions.remove(0);
					//	shutdown
					shutdownClientAsynch(client, shutdownMessageDelay, maxClientsShutdownMessage);
				}
			}
		}
		trace("Completed checking max clients");
	}

	
	private void checkClientsIdleTime(){
		
		//	no max idle time configured
		if(maxIdleTime <= 0){
			return;
		}
		
		//	check idle time
		long now = new Date().getTime();
		IClientInformation[] clients = server.getConnectedClients();
		for (int i = 0; i < clients.length; i++) {
			IClientInformation client = clients[0];
			int app = client.getApplicationType();
			if(app != IClientPluginAccess.WEB_CLIENT && app != IClientPluginAccess.CLIENT){
				continue;
			}
			long idleTime = now - client.getIdleTime().getTime();
			log("Client ID: "+client.getClientID()+", Host IP: "+client.getHostAddress()+", Idle Time: " + idleTime + ", Max Idle: " + maxIdleTime);
			if(idleTime > maxIdleTime){
				
				//	shutdown
				shutdownClientAsynch(client, shutdownMessageDelay, idleShutDownMessage);
			}
		}
		trace("Completed checking max idle time");
	}
	
	
	private void shutdownClientAsynch(final IClientInformation client, int delay, String msg){
		server.sendMessageToClient(client.getClientID(), msg);
		Runnable job = new Runnable(){
			@Override
			public void run() {
				server.shutDownClient(client.getClientID());
				log("Client ["+client.getClientID()+"] was shutdown");
			}
		};
		
		server.getExecutor().schedule(job, delay, TimeUnit.SECONDS);
		log("Client ["+client.getClientID()+"] signalled to shutdown in "+delay+" seconds");
	}
	
	private void log(String msg){
		Debug.log(LOG_PREFIX + ": " + msg);
	}
	private void warn(String msg){
		Debug.warn(LOG_PREFIX + ": " + msg);
	}
	private void error(String msg){
		Debug.error(LOG_PREFIX + ": " + msg);
	}
	private void trace(String msg){
		Debug.trace(LOG_PREFIX + ": " + msg);
	}
}
