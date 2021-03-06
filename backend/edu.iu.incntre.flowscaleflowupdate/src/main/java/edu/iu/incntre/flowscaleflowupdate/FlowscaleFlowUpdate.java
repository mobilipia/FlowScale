/** 
 * Copyright 2012 InCNTRE, This file is released under Apache 2.0 license except for component libraries under different licenses
http://www.apache.org/licenses/LICENSE-2.0
 */

package edu.iu.incntre.flowscaleflowupdate;

import grnoc.net.util.ipaddress.IPv4Address;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.openflow.protocol.OFFlowMod;
import org.openflow.protocol.OFMatch;
import org.openflow.protocol.OFPhysicalPort;
import org.openflow.protocol.OFType;
import org.openflow.protocol.action.OFAction;
import org.openflow.protocol.action.OFActionOutput;
import org.openflow.util.HexString;
import edu.iu.incntre.flowscale.FlowscaleController;
import edu.iu.incntre.flowscale.IPAddress;
import edu.iu.incntre.flowscale.SwitchDevice;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class will hot swap flows in order to remove flows from highly loaded
 * ports
 * 
 * 
 * @author Ali Khalfan (akhalfan@indiana.edu)
 * 
 * **/

public class FlowscaleFlowUpdate {

	protected static Logger logger = LoggerFactory
			.getLogger(FlowscaleFlowUpdate.class);
	private String databaseDriver;
	private String databaseClass;
	private static Connection conn;

	protected Thread hotSwappingThread;
	private FlowscaleController flowscaleController;
	private int intervalTime;

	private String dbUsername;
	private String dbPassword;
	private HashMap<Long, HashMap<Short, Short>> switchFlowMirrorPortsHashMap;

	private HashMap<Long, ArrayList<Short>> loadedPortsHashMap = new HashMap<Long, ArrayList<Short>>();
	double optimalPercentage;

	public void setDatabaseDriver(String databaseDriver) {
		this.databaseDriver = databaseDriver;
	}

	public void setDatabaseClass(String databaseClass) {
		this.databaseClass = databaseClass;
	}

	public void setFlowscaleController(FlowscaleController flowscaleController) {
		this.flowscaleController = flowscaleController;
	}

	public void setIntervalTime(int intervalTime) {

		this.intervalTime = intervalTime;

	}

	/**
	 * read loaded ports from config file
	 * 
	 * @param loadedPortsString
	 */
	public void setLoadedPorts(String loadedPortsString) {

		String[] splitByDatapath = loadedPortsString.split("-");

		for (String switchAndLoadedPorts : splitByDatapath) {
			String datapathIdString = switchAndLoadedPorts.split(":")[0];
			String loadedPortsFromDatapathId = switchAndLoadedPorts.split(":")[1];

			String[] loadedPortStrings = loadedPortsFromDatapathId.split(",");

			ArrayList<Short> loadedPorts = new ArrayList<Short>();

			for (String loadedPortString : loadedPortStrings) {

				loadedPorts.add(Short.parseShort(loadedPortString));

			}

			loadedPortsHashMap.put(HexString.toLong(datapathIdString),
					loadedPorts);

		}

	}

	public void setDbUsername(String dbUsername) {
		this.dbUsername = dbUsername;
	}

	public void setDbPassword(String dbPassword) {
		this.dbPassword = dbPassword;

	}

	/**
	 * starts up the bundle and creates a new thread that continuously reads the
	 * database
	 * 
	 */

	public void startUp() {

		try {
			Class.forName(databaseClass);
			conn = DriverManager.getConnection(databaseDriver, dbUsername,
					dbPassword);

		} catch (SQLException e) {
			// TODO Auto-generated catch block
			logger.error("{}", e);
		} catch (ClassNotFoundException e) {
			// TODO Auto-generated catch block
			logger.error("{}", e);
		}

		logger.info("Start up of flow updater ");
		try {
			hotSwappingThread = new Thread(new Runnable() {

				@Override
				public void run() {
					try {
						// get stats from database

						switchFlowMirrorPortsHashMap = flowscaleController.getSwitchFlowMirrorPortsHashMap();
						while (hotSwappingThread != null) {

							logger.info("in flow updater thread ");
							HashMap<Long, SwitchDevice> switchDevices = flowscaleController
									.getSwitchDevices();
							long queryTime = Calendar.getInstance()
									.getTimeInMillis();

							for (long datapathId : loadedPortsHashMap.keySet()) {
								logger.debug("checking switch {}", datapathId);
								if (switchDevices.get(datapathId) == null
										|| switchDevices.get(datapathId)
												.getOpenFlowSwitch() == null) {

									logger.info(
											"switch {} is not connected yet",
											HexString.toHexString(datapathId));
									continue;
								}

								HashMap<Short, Short> mirroredPorts = null;
								mirroredPorts = switchFlowMirrorPortsHashMap
										.get(datapathId);

								if (mirroredPorts == null) {
									mirroredPorts = new HashMap<Short, Short>();
								}

								ArrayList<Short> loadedPorts = new ArrayList<Short>();
								for (Short shortPort : loadedPortsHashMap
										.get(datapathId)) {

									loadedPorts.add(shortPort);

								}

								// end get stats from database

								// copy loaded ports into another arraylist
								// since some ports will be removed if they are
								// down during this iteration
								Collections.copy(loadedPorts,
										loadedPortsHashMap.get(datapathId));

								logger.debug("port list {}",
										flowscaleController.getSwitchDevices()
												.get(datapathId)
												.getPortStates());

								if (switchDevices.get(datapathId)
										.getPortStates() == null) {
									logger.info(
											"switch {} not ready for flow update yet, port states data structure is null ",
											HexString.toHexString(datapathId));
									continue;
								}

								// make sure only load balance on ports that are
								// up
								removeDownPorts(flowscaleController,
										datapathId, loadedPorts);

								// obtain optimal percentage for each loaded
								// ports
								optimalPercentage = (double) (100 / loadedPorts
										.size());

								ArrayList<OFFlowMod> flowMods = new ArrayList<OFFlowMod>();
						 
								long totalPacketCount = 0;

								ArrayList<LoadFlow> switchFlows = new ArrayList<LoadFlow>();

								try {

									// call db to populate flowstats structures
									// here

									getFlowsFromDB(datapathId, queryTime,
											loadedPorts, switchFlows);

									totalPacketCount = getTotalPacketCount(switchFlows);
									/* return totalPacketCount; */

									logger.info("total packet count {}",
											totalPacketCount);

									// get flow percentages, iterate over all
									// flow stats to get flow percentage

									setFlowsPercentage(switchFlows,
											totalPacketCount);

									// sort flow percent by ascending order
									Collections.sort(switchFlows,
											new LoadFlowComparator());

									// print debug messages

									for (LoadFlow switchFlow : switchFlows) {

										logger.info(
												"flow {} has percentage {}",
												switchFlow.getFlowString(),
												switchFlow.getFlowPercent());
										logger.info("its port is {}",
												switchFlow.getLoadedPort());

									}

									// end print debug messages

									// start the hot swapping

									HashMap<Short, TreeSet<LoadFlow>> newFlows;

									// call hot swapping method
									newFlows = hotSwap(totalPacketCount,
											switchFlows);

									// print debug messages after hot swapping

									for (Short increasedPorts : newFlows
											.keySet()) {

										for (LoadFlow changedFlow : newFlows
												.get(increasedPorts)) {

											logger.debug(
													"new flow {} in port {}",
													changedFlow.getFlowString(),
													increasedPorts);

										}

									}

									// end print debug messages after hot
									// swapping

									// convert string into flows readable by
									// openflowj
									convertFlowsToOpenflowJ(newFlows,
											mirroredPorts, flowMods);
									// call convert method

									logger.debug(
											"flowscale controller instance in thread is {} ",
											flowscaleController.toString());

								} catch (SQLException sqlE) {

									logger.error("{}", sqlE);

								}

								for (OFFlowMod flowMod : flowMods) {
									logger.debug(
											"prior to injection, flow is {}",
											flowMod);
								}

								// set percentages for every port;
								logger.debug(" flow mod  size is {} ",
										flowMods.size());
								
								logger.info("Number of Flows to swap is {}", flowMods.size());

								flowscaleController.injectFlows(flowMods,
										datapathId);

							}

							try {

								logger.info("Hot Swapping Completed!");
								Thread.sleep(intervalTime * 1000);

							} catch (InterruptedException e) {

								logger.error("{}", e);

								break;
							}

						}

					} catch (Exception runException) {
						logger.error(" {}", runException);
					}
				}
			}, "Hot Swapping Thread");
			hotSwappingThread.start();
		} catch (Exception e) {
			logger.error("{}", e);
		}

	}

	public void shutDown() {
	}

	private Short getMinPort(ArrayList<Short> lowPorts,
			HashMap<Short, Double> portPercentages) {

		if (lowPorts == null) {
			return null;
		}

		double minPercentage = Double.MAX_VALUE;

		Short minPort = null;
		for (Short lowPort : lowPorts) {

			if (portPercentages.get(lowPort) < this.optimalPercentage
					&& portPercentages.get(lowPort) < minPercentage) {

				minPercentage = portPercentages.get(lowPort);
				minPort = lowPort;

			}

		}

		return minPort;

	}

	public String getName() {

		return "flowUpdate";
	}

	/**
	 * takes the list of switchFlows and gets their total packets
	 * 
	 * @param switchFlows
	 * @return total packet count of the list of LoadFlow
	 */

	private long getTotalPacketCount(ArrayList<LoadFlow> switchFlows) {

		Iterator<LoadFlow> iterator = switchFlows.iterator();
		long totalPacketCount = 0;
		while (iterator.hasNext()) {

			totalPacketCount += iterator.next().getPacketCount();

		}
		return totalPacketCount;

	}

	/**
	 * retrieves the percentage of every port and populates the data structure
	 * on whether the ports are above or below the optimal percentage
	 * 
	 * @param totalPacketCount
	 *            used to calculate the percentage
	 * @param switchFlows
	 *            the list of LoadFlow which hosts the flows and their packet
	 *            count
	 * @param portPercentages
	 *            will be populated with each port and their percentages
	 * @param belowPercentPorts
	 *            will be populated with all ports less than the optimal
	 *            percentage or equal
	 * @param abovePercentPorts
	 *            will be populated with all ports greater than the optimal
	 *            percentage
	 */
	private void getPortsPercentages(long totalPacketCount,
			ArrayList<LoadFlow> switchFlows,
			HashMap<Short, Double> portPercentages,
			ArrayList<Short> belowPercentPorts,
			ArrayList<Short> abovePercentPorts) {

		// get port counts
		HashMap<Short, Long> portPacketCount = new HashMap<Short, Long>();
		Iterator<LoadFlow> loadFlowsIterator = switchFlows.iterator();
		LoadFlow loadFlow;
		while (loadFlowsIterator.hasNext()) {
			loadFlow = loadFlowsIterator.next();
			if (portPacketCount.get(loadFlow.getLoadedPort()) == null) {
				portPacketCount.put(loadFlow.getLoadedPort(), 0L);
			}
			portPacketCount.put(
					loadFlow.getLoadedPort(),
					portPacketCount.get(loadFlow.getLoadedPort())
							+ loadFlow.getPacketCount());

		}

		// obtain percentage for each port and assign if
		for (Short port : portPacketCount.keySet()) {
			Double percentageValue = new Double(0);
			// if totalPacketCount =0 is 0 do not divide (avoid arithmetic
			// exception)
			if (totalPacketCount == 0) {
				portPercentages.put(port, percentageValue);
			} else {

				percentageValue = (double) ((double) portPacketCount.get(port)
						/ (double) totalPacketCount * 100);
				logger.debug("port packet count for port {} is {}", port,
						portPacketCount.get(port));

				portPercentages.put(port, new Double(percentageValue));

			}
			if (percentageValue <= optimalPercentage) {
				belowPercentPorts.add(port);

			} else {

				abovePercentPorts.add(port);

			}

		}

		logger.debug("port packet count list {}", portPacketCount.toString());
		logger.debug("percentage list {}", portPercentages.toString());

	}

	/**
	 * The main method concertned with moving flows from high load ports to ones
	 * with lower loads
	 * 
	 * @param totalPacketCount
	 * @param switchFlows
	 * @return the new flows that will be injected to the switch to acheive
	 *         proper load balancing after running the hotswapping algorithm
	 */
	private HashMap<Short, TreeSet<LoadFlow>> hotSwap(long totalPacketCount,
			ArrayList<LoadFlow> switchFlows) {

		HashMap<Short, Double> portPercentages = new HashMap<Short, Double>();
		ArrayList<LoadFlow> checkedFlows = new ArrayList<LoadFlow>();
		ArrayList<Short> abovePercentPorts = new ArrayList<Short>();
		ArrayList<Short> belowPercentPorts = new ArrayList<Short>();

		getPortsPercentages(totalPacketCount, switchFlows, portPercentages,
				belowPercentPorts, abovePercentPorts);

		HashMap<Short, TreeSet<LoadFlow>> newFlows = new HashMap<Short, TreeSet<LoadFlow>>();

		Collections.sort(abovePercentPorts, new HighPortComparator(
				portPercentages));

		for (Short highPort : abovePercentPorts) {

			for (LoadFlow loadFlow : switchFlows) {

				// first a few validations on whether the flow can be swapped
				logger.debug("trying to move flows from high port {}", highPort);
				// flow must have the same action as port being checked
				if (!(loadFlow.getLoadedPort() == highPort)) {
					logger.debug("flow {} does not belong to high port  {}",
							loadFlow.getFlowString(), highPort);
					continue;
				}
				// make sure the flow is not of negligible value
				if (loadFlow.getFlowPercent().doubleValue() < 0.05) {
					logger.debug(
							"flow {} has percent {} which is  equal to 0 anyway",
							loadFlow.getFlowString(), loadFlow.getFlowPercent());
					continue;
				}
				// check that this flow has not been moved before, since we are
				// moving both source and destination
				// this may be possible
				if (checkedFlows.contains(loadFlow)) {
					logger.debug("flow {} is already in the checked list ",
							loadFlow.getFlowString());
					continue;
				}

				double flowPercentageValue = loadFlow.getFlowPercent();

				Short lowPort = this.getMinPort(belowPercentPorts,
						portPercentages);
				

				logger.debug("check if port {} with percentage {} can receive",
						lowPort, portPercentages.get(lowPort));

				String otherDirectFlow = "";

				if (loadFlow.getFlowString().indexOf("nw_src") != -1) {
					otherDirectFlow = loadFlow.getFlowString().replace(
							"nw_src", "nw_dst");

				} else if (loadFlow.getFlowString().indexOf("nw_dst") != -1) {
					otherDirectFlow = loadFlow.getFlowString().replace(
							"nw_dst", "nw_src");

				}
				LoadFlow otherDirectionLoadFlow = this.getFlowByString(
						otherDirectFlow, switchFlows);
				logger.info("other direction load flow is {}",
						otherDirectionLoadFlow);
				double totalNeededPercentage = (double) flowPercentageValue
						+ (double) (otherDirectionLoadFlow.getFlowPercent());

				//output used for debugging  issue # 36
				logger.info("porstPercantages is {}", portPercentages);
				
				logger.info("total needed percentage is {}",totalNeededPercentage);
				logger.info("low percentage is {}", portPercentages.get(lowPort));
				logger.info("high percentage is {}", portPercentages.get(highPort));
				logger.info("optimal percentage is {}", optimalPercentage);
				logger.info("below percentage ports {}", belowPercentPorts);
				logger.info("low port is {}", lowPort);
				
				
				
				if ( 
						((totalNeededPercentage < (optimalPercentage - portPercentages.get(lowPort) )) 
						|| (
								(portPercentages.get(highPort) - portPercentages
						.get(lowPort)) >= (double) (optimalPercentage / 2))
						)
						&& totalNeededPercentage < ((double) optimalPercentage / 2) 
						
				) {

					// add to newFlows
					logger.debug(
							"success in moving from high port {} with flow {}",
							highPort, loadFlow.getFlowString());
					if (newFlows.get(lowPort) == null) {

						TreeSet<LoadFlow> updatedFlows = new TreeSet<LoadFlow>();
						newFlows.put(lowPort, updatedFlows);

					}

					newFlows.get(lowPort).add(loadFlow);

					checkedFlows.add(this.getFlowByString(otherDirectFlow,
							switchFlows));
					checkedFlows.add(loadFlow);

					// subtract flow percentage
					// from
					// high load port

					double portPercentage = portPercentages.get(highPort);
					portPercentages
							.put(highPort,
									(double) ((double) portPercentage
											- (double) flowPercentageValue - (double) this
											.getFlowByString(otherDirectFlow,
													switchFlows)
											.getFlowPercent()));

					// add flow percentage to low load port
					double lowPortPercentage = portPercentages.get(lowPort);
					portPercentages
							.put(lowPort,
									(double) ((double) lowPortPercentage
											+ (double) flowPercentageValue + (double) this
											.getFlowByString(otherDirectFlow,
													switchFlows)
											.getFlowPercent()));

					logger.debug("port {} new percentage is {}", lowPort,
							portPercentages.get(lowPort));

					if (portPercentages.get(highPort) <= optimalPercentage) {

						break;

					}

					if (portPercentages.get(lowPort) >= optimalPercentage) {
						belowPercentPorts.remove(lowPort);
					}

				}
				logger.debug("can't move flow {} to low port {}",
						loadFlow.getFlowString(), lowPort);

			}
		}
		logger.debug("port percentages after hot swap {}", portPercentages);
		return newFlows;
	}

	/**
	 * remove any port that is down so that the hot swapping algorithm will not
	 * direct flows to offline port
	 * 
	 * @param flowscaleController
	 * @param datapathId
	 * @param loadedPorts
	 */
	private static void removeDownPorts(
			FlowscaleController flowscaleController, long datapathId,
			ArrayList<Short> loadedPorts) {

		for (int i = 0; i < flowscaleController.getSwitchDevices()
				.get(datapathId).getPortStates().size(); i++) {
			OFPhysicalPort checkedPorts = flowscaleController
					.getSwitchDevices().get(datapathId).getPortStates().get(i);

			if (loadedPorts.contains(checkedPorts.getPortNumber())) {

				if (checkedPorts.getState() % 2 != 0) {

					loadedPorts.remove(new Short(checkedPorts.getPortNumber()));

				}

			}

		}

	}

	/**
	 * convert the flows from string formats to an openflowj format
	 * 
	 * @param newFlows
	 * @param mirroredPorts
	 * @param flowMods
	 */
	private static void convertFlowsToOpenflowJ(
			HashMap<Short, TreeSet<LoadFlow>> newFlows,
			HashMap<Short, Short> mirroredPorts, ArrayList<OFFlowMod> flowMods) {
		for (Short increasedPort : newFlows.keySet()) {

			for (LoadFlow changedFlow : newFlows.get(increasedPort)) {

				OFMatch ofMatch = null;

				Pattern pattern = Pattern
						.compile("nw_src=([0-9]+.[0-9]+.[0-9]+.[0-9]+)/([0-9]*)");

				Matcher matcher = pattern.matcher(changedFlow.getFlowString());

				while (matcher.find()) {
					String fullValue = matcher.group()
							.replaceAll("nw_src=", "");

					String[] ipAndSubnet = fullValue.split("/");

					try {
						logger.debug("ip address is {} and subnet is {}",

						matcher.group(1), matcher.group(2));

					} catch (IndexOutOfBoundsException ioe) {
						break;
					}
					IPv4Address ipv4Address = new IPv4Address(ipAndSubnet[0]);
					ipv4Address.setSubnet(Integer.parseInt(ipAndSubnet[1]));
					int ipAddressInt = ipv4Address.getIPv4AddressInt();

					IPAddress ipAddress = new IPAddress();
					ipAddress.setIpAddressValue(ipAddressInt);
					ipAddress.setSubnet(Integer.parseInt(ipAndSubnet[1]));

					short maskingBits = (short) (ipAddress.getSubnet());
					int wildCardSource = OFMatch.OFPFW_ALL
							^ OFMatch.OFPFW_DL_TYPE
							^ OFMatch.OFPFW_NW_SRC_ALL
							^ (((maskingBits) - 1) << OFMatch.OFPFW_NW_SRC_SHIFT);

					OFMatch ofMatchSource = new OFMatch();
					ofMatchSource.setDataLayerType((short) 0x0800);

					ofMatchSource.setNetworkSource(ipAddress
							.getIpAddressValue());

					ofMatchSource.setWildcards(wildCardSource);
					ofMatch = ofMatchSource;
					OFFlowMod flowModRule = new OFFlowMod();
					flowModRule.setCommand(OFFlowMod.OFPFC_MODIFY_STRICT);
					flowModRule.setPriority(changedFlow.getPriority());
					flowModRule.setHardTimeout((short) 0);
					flowModRule.setIdleTimeout((short) 0);
					
					flowModRule.setBufferId(-1);
					flowModRule.setMatch(ofMatch);
			

					
					OFActionOutput ofAction = new OFActionOutput();
					ofAction.setPort(increasedPort);
					ArrayList<OFAction> actions = new ArrayList<OFAction>();
					actions.add(ofAction);
					if (mirroredPorts.get(increasedPort) != null) {
						OFActionOutput ofActionMirror = new OFActionOutput();
						ofActionMirror
								.setPort(mirroredPorts.get(increasedPort));
						actions.add(ofActionMirror);
					}

					flowModRule.setActions(actions);

	

					flowMods.add(flowModRule);

					OFMatch ofMatchDest = new OFMatch();
					ofMatchDest.setDataLayerType((short) 0x0800);
					ofMatchDest.setNetworkDestination(ipAddress
							.getIpAddressValue());

					int wildCardDest = OFMatch.OFPFW_ALL
							^ OFMatch.OFPFW_DL_TYPE
							^ OFMatch.OFPFW_NW_DST_ALL
							^ (((maskingBits) - 1) << OFMatch.OFPFW_NW_DST_SHIFT);

					ofMatchDest.setWildcards(wildCardDest);

					OFFlowMod flowModRule1 = new OFFlowMod();
					flowModRule1.setCommand(OFFlowMod.OFPFC_MODIFY_STRICT);
					flowModRule1.setPriority(changedFlow.getPriority());
					flowModRule1.setHardTimeout((short) 0);
					flowModRule1.setIdleTimeout((short) 0);
					flowModRule1.setBufferId(-1);
					flowModRule1.setMatch(ofMatchDest);

					flowModRule1.setActions(actions);

				

					flowMods.add(flowModRule1);

				}

				pattern = Pattern
						.compile("nw_dst=([0-9]+.[0-9]+.[0-9]+.[0-9]+)/([0-9]*)");

				matcher = pattern.matcher(changedFlow.getFlowString());

				while (matcher.find()) {

					try {
						logger.debug("ip address is {} and subnet is {}",

						matcher.group(1), matcher.group(2));

					} catch (IndexOutOfBoundsException ioe) {
						break;
					}
					IPv4Address ipv4Address = new IPv4Address(matcher.group(1));
					ipv4Address.setSubnet(Integer.parseInt(matcher.group(2)));
					int ipAddressInt = ipv4Address.getIPv4AddressInt();

					IPAddress ipAddress = new IPAddress();
					ipAddress.setIpAddressValue(ipAddressInt);
					ipAddress.setSubnet(Integer.parseInt(matcher.group(2)));

					short maskingBits = (short) (ipAddress.getSubnet());
					int wildCardDestination = OFMatch.OFPFW_ALL
							^ OFMatch.OFPFW_DL_TYPE
							^ OFMatch.OFPFW_NW_DST_ALL
							^ (((maskingBits) - 1) << OFMatch.OFPFW_NW_DST_SHIFT);

					OFMatch ofMatchDestination = new OFMatch();
					ofMatchDestination.setDataLayerType((short) 0x0800);

					ofMatchDestination.setNetworkDestination(ipAddress
							.getIpAddressValue());

					ofMatchDestination.setWildcards(wildCardDestination);
					ofMatch = ofMatchDestination;
					OFFlowMod flowModRule = new OFFlowMod();
					flowModRule.setCommand(OFFlowMod.OFPFC_MODIFY_STRICT);
					flowModRule.setHardTimeout((short) 0);
					flowModRule.setIdleTimeout((short) 0);
					flowModRule.setBufferId(-1);
					flowModRule.setMatch(ofMatch);
					flowModRule.setPriority(changedFlow.getPriority());
					
					OFActionOutput ofAction = new OFActionOutput();
					ofAction.setPort(increasedPort);
					ArrayList<OFAction> actions = new ArrayList<OFAction>();
					actions.add(ofAction);
					if (mirroredPorts.get(increasedPort) != null) {
						OFActionOutput ofActionMirror = new OFActionOutput();
						ofActionMirror
								.setPort(mirroredPorts.get(increasedPort));
						actions.add(ofActionMirror);
					}
					flowModRule.setActions(actions);

				

					flowMods.add(flowModRule);

					OFMatch ofMatchSrc = new OFMatch();
					ofMatchSrc.setDataLayerType((short) 0x0800);
					ofMatchSrc.setNetworkSource(ipAddress.getIpAddressValue());

					int wildCardSrc = OFMatch.OFPFW_ALL
							^ OFMatch.OFPFW_DL_TYPE
							^ OFMatch.OFPFW_NW_SRC_ALL
							^ (((maskingBits) - 1) << OFMatch.OFPFW_NW_SRC_SHIFT);

					ofMatchSrc.setWildcards(wildCardSrc);

					OFFlowMod flowModRule1 = new OFFlowMod();
					flowModRule1.setCommand(OFFlowMod.OFPFC_MODIFY_STRICT);
					flowModRule1.setPriority(changedFlow.getPriority());
					flowModRule1.setHardTimeout((short) 0);
					flowModRule1.setIdleTimeout((short) 0);
					flowModRule1.setBufferId(-1);
					flowModRule1.setMatch(ofMatchSrc);

					flowModRule1.setActions(actions);

		

					flowMods.add(flowModRule1);

				}

			}

		}

	}

	/**
	 * 
	 * This method iterates over the list of LoadFlows and sets the percentages
	 * of each LoadFlow object based on the packet count
	 * 
	 * @param switchFlows
	 * @param totalPacketCount
	 * 
	 */
	private void setFlowsPercentage(ArrayList<LoadFlow> switchFlows,
			long totalPacketCount) {

		Iterator<LoadFlow> iterator = switchFlows.iterator();
		LoadFlow current;
		while (iterator.hasNext()) {
			current = iterator.next();
			if (totalPacketCount == 0) {
				current.setFlowPercent((double) 0d);
			} else {

				current.setFlowPercent((double) ((double) current
						.getPacketCount() / (double) totalPacketCount) * 100);
			}

		}

	}

	/**
	 * reads database and returns the flows for a given time interval (e.g last
	 * 5 minutes) if there is more than one entry for a flow then the packet
	 * counts will be accumulated. each flow will only be represented once in
	 * the list
	 * 
	 * @param datapathId
	 *            switch's datapathId used to query the database by datapath id
	 * @param queryTime
	 *            how far to query
	 * @param loadedPorts
	 *            only look for flows containing the loaded ports
	 * @param switchFlows
	 *            object used to store all the LoadFlows, for every flow an
	 *            object will be instantiated
	 * @throws SQLException
	 */
	private void getFlowsFromDB(long datapathId, long queryTime,
			ArrayList<Short> loadedPorts, ArrayList<LoadFlow> switchFlows)
			throws SQLException {

		String flowStatQuery = "SELECT datapath_id, match_string, action, packet_count,priority FROM flow_stats where datapath_id = ? AND  timestamp >= ?";
		PreparedStatement flowStatPs = null;
		ResultSet flowStatRs = null;
		flowStatPs = conn.prepareStatement(flowStatQuery);
		flowStatPs.setLong(1, datapathId);
		flowStatPs.setLong(2, queryTime - (intervalTime * 1000));

		flowStatRs = flowStatPs.executeQuery();

		logger.debug(
				"query : SELECT datapath_id, match_string, action, packet_count priority FROM flow_stats where datapath_id = {} AND  timestamp >= {} ",
				datapathId, queryTime - (intervalTime * 1000));

		while (flowStatRs.next()) {

			String matchString = flowStatRs.getString(2);
			String action = flowStatRs.getString(3);
			long packetCount = flowStatRs.getLong(4);
			short priority = flowStatRs.getShort(5);
			
			if (!(matchString.contains("nw_src") || matchString
					.contains("nw_dst"))) {

				// skip saving of flow if not layer 3
				continue;
			}

			Short loadedPort = 0;
			logger.trace("obtained  flow {} with packet count {}", matchString,
					packetCount);

			String[] actions = action.split(",");
			try {
				loadedPort = Short.parseShort(actions[0]);

			} catch (NumberFormatException nfe) {

				continue;

			}
			logger.debug("and port is {}", loadedPort);
			if (loadedPorts.contains(loadedPort)) {

				// add new flow here, or if exist increment packet count
				LoadFlow tempLoadFlow = new LoadFlow(matchString, loadedPort,priority);

				LoadFlow loadFlowInstance = getFlowByString(matchString,
						switchFlows);
				if (loadFlowInstance != null) {
					if (loadFlowInstance.getLoadedPort() != loadedPort) {
						switchFlows.remove(loadFlowInstance);
						tempLoadFlow.setPacketCount(packetCount);
						switchFlows.add(tempLoadFlow);
					} else {
						logger.trace("tempLoad Flow is {}", loadFlowInstance);
						loadFlowInstance.setPacketCount(loadFlowInstance
								.getPacketCount() + packetCount);
					}

				} else {

					logger.trace("adding flow {}", tempLoadFlow);

					tempLoadFlow.setPacketCount(packetCount);
					logger.trace("adding of switch flow is {}",
							switchFlows.add(tempLoadFlow));
					;
				}

			}
		}

	}

	/**
	 * 
	 * @param findString
	 *            the String that will be used to retrieve e.g.
	 *            "OFMatch[dl_type=0x800,nw_dst=192.168.0.0/21]"
	 * @param switchFlows
	 * @return LoadFlow object that contains the particular String as an
	 *         attribute or null if that object is not found
	 */
	private LoadFlow getFlowByString(String findString,
			ArrayList<LoadFlow> switchFlows) {
		logger.trace("trying to locate flow {}", findString);
		Iterator<LoadFlow> it = switchFlows.iterator();
		LoadFlow checkedLoadFlow;
		logger.trace("switch flow size {}", switchFlows.size());
		while (it.hasNext()) {

			checkedLoadFlow = it.next();

			if (checkedLoadFlow.getFlowString().equals(findString)) {
				return checkedLoadFlow;
			}

		}
		logger.debug("flow not found");
		return null;
	}

}

class LoadFlowComparator implements Comparator<Object> {

	@Override
	public int compare(Object flow1, Object flow2) {

		Double percent1 = ((LoadFlow) flow1).getFlowPercent();
		Double percent2 = ((LoadFlow) flow2).getFlowPercent();

		Short port1 = ((LoadFlow) flow1).getLoadedPort();
		Short port2 = ((LoadFlow) flow2).getLoadedPort();

		String flowString1 = ((LoadFlow) flow1).toString();
		String flowString2 = ((LoadFlow) flow2).toString();

		if (flowString1.equals(flowString2) && port1 == port2) {
			return 0;
		} else if (!flowString1.equals(flowString2) && percent1 > percent2) {
			return 1;
		} else if (!flowString1.equals(flowString2) & percent1 < percent2) {
			return -1;
		} else {
			return 1;
		}

	}
}

class HighPortComparator implements Comparator<Object> {

	HashMap<Short, Double> portPercentage;

	public HighPortComparator() {

	}

	public HighPortComparator(HashMap<Short, Double> portPercentage) {
		this.portPercentage = portPercentage;

	}

	@Override
	public int compare(Object port1, Object port2) {

		Double percentage1 = portPercentage.get(((Short) port1));
		Double percentage2 = portPercentage.get(((Short) port2));

		if (percentage1 < percentage2) {
			return 1;
		} else if (percentage1 > percentage2) {
			return -1;
		} else {
			return 0;
		}

	}

}

class LowPortComparator implements Comparator<Object> {

	HashMap<Short, Double> portPercentage;

	public LowPortComparator() {

	}

	public LowPortComparator(HashMap<Short, Double> portPercentage) {
		this.portPercentage = portPercentage;

	}

	@Override
	public int compare(Object port1, Object port2) {

		Double percentage1 = portPercentage.get(((Short) port1));
		Double percentage2 = portPercentage.get(((Short) port2));

		if (percentage1 > percentage2) {
			return 1;
		} else if (percentage1 < percentage2) {
			return -1;
		} else {
			return 0;
		}

	}

}
