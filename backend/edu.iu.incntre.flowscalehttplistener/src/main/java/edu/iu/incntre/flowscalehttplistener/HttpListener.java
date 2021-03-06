/** 
 * Copyright 2012 InCNTRE, This file is released under Apache 2.0 license except for component libraries under different licenses
http://www.apache.org/licenses/LICENSE-2.0
 */

package edu.iu.incntre.flowscalehttplistener;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import javax.servlet.http.*;
import javax.servlet.*;

import org.json.simple.JSONObject;

import org.mortbay.jetty.Handler;
import org.mortbay.jetty.HttpConnection;
import org.mortbay.jetty.Request;
import org.mortbay.jetty.Server;
import org.openflow.protocol.OFPhysicalPort;
import org.openflow.protocol.OFPhysicalPort.OFPortConfig;
import org.openflow.protocol.OFPortMod;
import org.openflow.protocol.OFType;
import org.openflow.protocol.statistics.OFStatistics;
import org.openflow.util.HexString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.iu.incntre.flowscale.FlowscaleController;
import edu.iu.incntre.flowscale.SwitchDevice;

import org.openflow.protocol.OFPhysicalPort;
import net.beaconcontroller.core.IOFSwitch;

import edu.iu.incntre.flowscale.exception.NoSwitchException;
import edu.iu.incntre.flowscale.util.JSONConverter;

/**
 * This class is an Http interface, it is a listener to Http requests directed
 * to the port it is listening to, mainly can handle REST or other calls
 * 
 * @author Ali Khalfan (akhalfan@indiana.edu)
 */

public class HttpListener implements Handler {

	protected FlowscaleController flowscaleController;
	// protected FlowscaleFlowUpdate flowscaleUpdater;
	Server server;
	protected int jettyListenerPort;
	protected static Logger logger = LoggerFactory
			.getLogger(HttpListener.class);

	public String getName() {
		// TODO Auto-generated method stub
		return "flowscaleHttpListener";
	}

	public FlowscaleController getFlowscaleController() {

		return this.flowscaleController;

	}

	public void setFlowscaleController(FlowscaleController flowscaleController) {
		this.flowscaleController = flowscaleController;
	}

	public void setFlowscale(FlowscaleController flowscaleController) {
		this.flowscaleController = flowscaleController;
	}

	public void setJettyListenerPort(int port) {

		this.jettyListenerPort = port;
	}

	@Override
	/** 
	 * method will call other other methods based on the Http Message most 
	 * methods call other bundles 
	 */
	public void handle(String arg0, HttpServletRequest request,
			HttpServletResponse response, int arg3) throws IOException,
			ServletException {
		// TODO Auto-generated method stub

		logger.debug("http request received");
		logger.debug("request header type is {}", request.getMethod());

		Request base_request = (request instanceof Request) ? (Request) request
				: HttpConnection.getCurrentConnection().getRequest();
		base_request.setHandled(true);
		response.setContentType("text/html;charset=utf-8");

		String output = "";
		JSONObject obj;
		if (request.getMethod() != "GET") {

			response.setStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED);

			 obj = new JSONObject();
			obj.put("success", 0);
			obj.put("error", "method not allowed");
			response.getWriter().print(obj);

			return;
		}

		String requestAction = request.getHeader("action");

		logger.debug("action is {}", requestAction);
		if (requestAction.equals("getSwitchStatus")) {

		} else if (requestAction.equals("getSwitchPorts")) {

			long datapathId = HexString.toLong(request.getHeader("datapathId"));

			if (flowscaleController.getSwitchDevices().get(datapathId) == null) {
				logger.error("Switch  {} is not connected to the controller ",
						HexString.toHexString(datapathId));
				output = "";
			} else {
				List<OFPhysicalPort> portList = flowscaleController
						.getSwitchDevices().get(datapathId).getPortStates();

				if (portList == null) {
					logger.error("no switch connected yet ");
				} else {
					output = JSONConverter.toPortStatus(portList)
							.toJSONString();
				}
			}

		}

		else if (requestAction.equals("addSwitch")) {

			logger.debug(("action is to add Switch"));
			flowscaleController.addSwitchFromInterface(request
					.getHeader("datapathId"));

		} else if (requestAction.equals("removeSwitch")) {

			flowscaleController.removeSwitchFromInterface(request
					.getHeader("datapathId"));

		} else if (requestAction.equals("addGroup")) {

			// get all values for the grouop

			String groupIdString = request.getHeader("groupId");
			String groupName = request.getHeader("groupName");
			String inputSwitchDatapathIdString = request
					.getHeader("inputSwitch");
			String outputSwitchDatapathIdString = request
					.getHeader("outputSwitch");
			String inputPortListString = request.getHeader("inputPorts");
			String outputPortListString = request.getHeader("outputPorts");
			String typeString = request.getHeader("type");
			String priorityString = request.getHeader("priority");
			String valuesString = request.getHeader("values");
			String maximumFlowsAllowedString = request
					.getHeader("maximumFlowsAllowed");
			String networkProtocolString = request.getHeader("networkProtocol");
			String transportDestinationString = request
					.getHeader("transportDirection");

			// end get all values for the gorup

			output = flowscaleController.addGroupFromInterface(groupIdString,
					groupName, inputSwitchDatapathIdString,
					outputSwitchDatapathIdString, inputPortListString,
					outputPortListString, typeString, priorityString,
					valuesString, maximumFlowsAllowedString,
					networkProtocolString, transportDestinationString);

		}

		else if (requestAction.equals("editGroup")) {

			String groupIdString = request.getHeader("groupId");
			String editTypeString = request.getHeader("editType");
			String updateValueString = request.getHeader("updateValue");

			flowscaleController.editGroupFromInterface(groupIdString,
					editTypeString, updateValueString);

			// parse command ,
			// the right methods

		} else if (requestAction.equals("deleteGroup")) {

			logger.debug("group id is {}", request.getHeader("groupId"));

			flowscaleController.deleteGroupFromInterface(request
					.getHeader("groupId"));

		} else if (requestAction.equals("getSwitchStatistics")) {

			try {
				List<OFStatistics> ofs = flowscaleController
						.getSwitchStatisticsFromInterface(
								request.getHeader("datapathId"),
								request.getHeader("type"));

				output = JSONConverter.toStat(ofs, request.getHeader("type"))
						.toJSONString();

			} catch (NoSwitchException e) {
				// TODO Auto-generated catch block
				logger.error("Switch is not connected", e);
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				logger.error("Thread Interrupted {}", e);
			} catch (ExecutionException e) {
				// TODO Auto-generated catch block
				logger.error("Execution Aborted {}", e);
			} catch (TimeoutException e) {
				logger.error("Request to switch timed out");
			}

		} else if (requestAction.equals("sensorUpdate")) {

			String datapathIdString = request.getHeader("datapathId");
			String sensorIdString = request.getHeader("sensorId");
			String updateStatus = request.getHeader("updateStatus");
logger.info("datapathId {} sensorId {}", datapathIdString,sensorIdString);
logger.info("update status {}", updateStatus);

			try {

				SwitchDevice switchDevice = flowscaleController
						.getSwitchDevices().get(
								HexString.toLong(datapathIdString));

				IOFSwitch sw = switchDevice.getOpenFlowSwitch();
				List<OFPhysicalPort> switchPorts = switchDevice.getPortStates();
				OFPhysicalPort updatedPort = null;

				for (OFPhysicalPort ofPort : switchPorts) {

					if (ofPort.getPortNumber() == Short
							.parseShort(sensorIdString)) {
						updatedPort = ofPort;
						break;

					}

				}

				updatedPort.setConfig(0);

				OFPortMod portMod = new OFPortMod();

				if (updateStatus.equals("down")) {
					portMod.setConfig(OFPhysicalPort.OFPortConfig.OFPPC_PORT_DOWN
							.getValue());
				} else if (updateStatus.equals("up")) {
					portMod.setConfig(0);
				}

				portMod.setMask(1);
				portMod.setPortNumber(updatedPort.getPortNumber());
				portMod.setHardwareAddress(updatedPort.getHardwareAddress());
				portMod.setType(OFType.PORT_MOD);

				sw.getOutputStream().write(portMod);
				sw.getOutputStream().flush();
				obj = new JSONObject();
				obj.put("result", "success");
				obj.put("desc", "sensor "+sensorIdString+" marked as "+updateStatus +
						" on switch "+ datapathIdString);
				response.getWriter().print(obj);

			} catch (Exception e) {
				logger.error("{}", e);
				obj = new JSONObject();
				obj.put("result", "failure");
				obj.put("desc", e.toString());
				response.getWriter().print(obj);
			}

		}

		response.setStatus(HttpServletResponse.SC_OK);

		logger.debug("response output {}", output);
		
		response.getWriter().print(output);

	}

	public void startUp() {
		Handler handler = this;

		logger.info("starting http server at port *:{}", jettyListenerPort);
		server = new Server(jettyListenerPort);

		server.setHandler(handler);

		try {
			server.start();
		} catch (Exception e) {
			// TODO Auto-generated catch block
			logger.error("{}", e);

		}

	}

	public void shutDown() {

		try {
			server.stop();
		} catch (Exception e) {
			// TODO Auto-generated catch block
			logger.info("{}", e);
		}
	}

	@Override
	public void addLifeCycleListener(Listener arg0) {
		// TODO Auto-generated method stub

	}

	@Override
	public void setServer(Server arg0) {
		// TODO Auto-generated method stub

	}

	@Override
	public boolean isFailed() {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean isRunning() {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean isStarted() {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean isStarting() {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean isStopped() {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean isStopping() {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public void removeLifeCycleListener(Listener arg0) {
		// TODO Auto-generated method stub

	}

	@Override
	public void start() throws Exception {
		// TODO Auto-generated method stub

	}

	@Override
	public void stop() throws Exception {
		// TODO Auto-generated method stub

	}

	@Override
	public void destroy() {
		// TODO Auto-generated method stub

	}

	@Override
	public Server getServer() {
		// TODO Auto-generated method stub
		return server;
	}

}
