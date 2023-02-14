/*
 * Copyright 2022-present Open Networking Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package nctu.winlab.unicastdhcp;

import static org.onosproject.net.config.NetworkConfigEvent.Type.CONFIG_ADDED;
import static org.onosproject.net.config.NetworkConfigEvent.Type.CONFIG_UPDATED;
import static org.onosproject.net.config.basics.SubjectFactories.APP_SUBJECT_FACTORY;

import org.onlab.packet.Ethernet;
import org.onlab.packet.IPv4;
import org.onlab.packet.MacAddress;
import org.onlab.packet.TpPort;
import org.onlab.packet.UDP;
import org.onosproject.cfg.ComponentConfigService;
import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;
import org.onosproject.net.ConnectPoint;
import org.onosproject.net.DeviceId;
import org.onosproject.net.FilteredConnectPoint;
import org.onosproject.net.PortNumber;
import org.onosproject.net.config.ConfigFactory;
import org.onosproject.net.config.NetworkConfigEvent;
import org.onosproject.net.config.NetworkConfigListener;
import org.onosproject.net.config.NetworkConfigRegistry;
import org.onosproject.net.flow.DefaultTrafficSelector;
import org.onosproject.net.flow.DefaultTrafficTreatment;
import org.onosproject.net.flow.TrafficSelector;
import org.onosproject.net.flow.TrafficTreatment;
import org.onosproject.net.host.HostService;
import org.onosproject.net.intent.IntentService;
import org.onosproject.net.packet.DefaultOutboundPacket;
import org.onosproject.net.packet.InboundPacket;
import org.onosproject.net.packet.OutboundPacket;
import org.onosproject.net.packet.PacketContext;
import org.onosproject.net.packet.PacketPriority;
import org.onosproject.net.packet.PacketProcessor;
import org.onosproject.net.packet.PacketService;
import org.onosproject.net.intent.Key;
import org.onosproject.net.intent.PointToPointIntent;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Dictionary;
import java.util.Properties;

import static org.onlab.util.Tools.get;

/**
 * Skeletal ONOS application component.
 */
@Component(immediate = true,
           service = {SomeInterface.class},
           property = {
               "someProperty=Some Default String Value",
           })
public class AppComponent implements SomeInterface {

    private final Logger log = LoggerFactory.getLogger(getClass());
    private final DhcpConfigListener cfgListener = new DhcpConfigListener();
    private DhcpPacketProcessor processor = new DhcpPacketProcessor();
    private final ConfigFactory<ApplicationId, DhcpConfig> factory = new ConfigFactory<ApplicationId, DhcpConfig>(
        APP_SUBJECT_FACTORY, DhcpConfig.class, "UnicastDhcpConfig") {
        @Override
        public DhcpConfig createConfig() {
            return new DhcpConfig();
        }
    };

    /** Some configurable property. */
    private String someProperty;

    private ApplicationId appId;
    private DeviceId serverId;
    private PortNumber serverPort;
    private String serverLocation;
    private MacAddress clientMAC;
    private FilteredConnectPoint clientCP;
    private FilteredConnectPoint serverCP;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected ComponentConfigService cfgService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected NetworkConfigRegistry networkConfigRegistry;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected CoreService coreService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected PacketService packetService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected IntentService intentService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected HostService hostService;

    @Activate
    protected void activate() {
        cfgService.registerProperties(getClass());
        appId = coreService.registerApplication("nctu.winlab.unicastdhcp");
        networkConfigRegistry.addListener(cfgListener);
        networkConfigRegistry.registerConfigFactory(factory);

        packetService.addProcessor(processor, PacketProcessor.director(2));
        requestIntercepts();

        log.info("Started==========");
    }

    @Deactivate
    protected void deactivate() {
        cfgService.unregisterProperties(getClass(), false);
        networkConfigRegistry.removeListener(cfgListener);
        networkConfigRegistry.unregisterConfigFactory(factory);

        packetService.removeProcessor(processor);
        withdrawIntercepts();

        log.info("Stopped==========");
    }

    @Modified
    public void modified(ComponentContext context) {
        Dictionary<?, ?> properties = context != null ? context.getProperties() : new Properties();
        if (context != null) {
            someProperty = get(properties, "someProperty");
        }
        log.info("Reconfigured");
    }

    @Override
    public void someMethod() {
        log.info("Invoked");
    }

    private class DhcpConfigListener implements NetworkConfigListener {
        @Override
        public void event(NetworkConfigEvent event) {
            if((event.type() == CONFIG_ADDED || event.type() == CONFIG_UPDATED)
                && event.configClass().equals(DhcpConfig.class)) {
                DhcpConfig config = networkConfigRegistry.getConfig(appId, DhcpConfig.class);
                if(config != null){
                    serverId = DeviceId.deviceId(config.serverLocation().substring(0, 19));
                    serverPort = PortNumber.portNumber(config.serverLocation().substring(20));
                    serverCP = new FilteredConnectPoint(new ConnectPoint(serverId, serverPort));
                    serverLocation = config.serverLocation();

                    log.info("DHCP server is connected to `{}`, port `{}`",
                                config.serverLocation().substring(0, 19),
                                config.serverLocation().substring(20));
                }
            }
        }
    }

    private void requestIntercepts() {
        TrafficSelector.Builder selector = DefaultTrafficSelector.builder();
        selector.matchEthType(Ethernet.TYPE_IPV4)
                .matchIPProtocol(IPv4.PROTOCOL_UDP)
                .matchUdpDst(TpPort.tpPort(UDP.DHCP_SERVER_PORT))
                .matchUdpSrc(TpPort.tpPort(UDP.DHCP_CLIENT_PORT));
        packetService.requestPackets(selector.build(), PacketPriority.CONTROL, appId);

        // selector = DefaultTrafficSelector.builder();
        // selector.matchEthType(Ethernet.TYPE_ARP);
        // packetService.requestPackets(selector.build(), PacketPriority.CONTROL, appId);
    }

    private void withdrawIntercepts() {
        TrafficSelector.Builder selector = DefaultTrafficSelector.builder();
        selector.matchEthType(Ethernet.TYPE_IPV4)
                .matchIPProtocol(IPv4.PROTOCOL_UDP)
                .matchUdpDst(TpPort.tpPort(UDP.DHCP_SERVER_PORT))
                .matchUdpSrc(TpPort.tpPort(UDP.DHCP_CLIENT_PORT));
        packetService.cancelPackets(selector.build(), PacketPriority.CONTROL, appId);

        // selector = DefaultTrafficSelector.builder();
        // selector.matchEthType(Ethernet.TYPE_ARP);
        // packetService.cancelPackets(selector.build(), PacketPriority.CONTROL, appId);
    }

    private class DhcpPacketProcessor implements PacketProcessor {
        
        @Override
        public void process(PacketContext context){
            if(context.isHandled()) {
                return;
            }

            InboundPacket pkt = context.inPacket();
            Ethernet ethPkt = pkt.parsed();
            if(ethPkt == null) {
                return;
            }

            // set up connectivity
            ConnectPoint cp = context.inPacket().receivedFrom();
            FilteredConnectPoint ingressPoint = new FilteredConnectPoint(cp);;
            FilteredConnectPoint egressPoint = serverCP;

            String tmpIngress = cp.deviceId().toString() +"/" + cp.port().toString();

            TrafficSelector selector = DefaultTrafficSelector.emptySelector();
            TrafficTreatment treatment = DefaultTrafficTreatment.emptyTreatment();
            
            if(tmpIngress.compareTo(serverLocation) != 0){
                clientCP = ingressPoint;
                clientMAC = ethPkt.getSourceMAC();
                // log.info("client -> server");
                // log.info("src: {}, {}", cp.deviceId(), cp.port());
                // log.info("dst: {}, {}", egressPoint.connectPoint().deviceId(), egressPoint.connectPoint().port());
            }else{
                egressPoint = clientCP;
                selector = DefaultTrafficSelector.builder().matchEthDst(clientMAC).build();
                // log.info("server -> client");
                // log.info("src: {}, {}", cp.deviceId(), cp.port());
                // log.info("dst: {}, {}", egressPoint.connectPoint().deviceId(), egressPoint.connectPoint().port());
            }
            

            String ingress = ingressPoint.connectPoint().deviceId().toString() + "/" + ingressPoint.connectPoint().port().toString();
            String egress = egressPoint.connectPoint().deviceId().toString() + "/" + egressPoint.connectPoint().port().toString();

            // log.info(ingress + egress);

            Key key = Key.of(ingress + egress, appId);

            PointToPointIntent intent = (PointToPointIntent) intentService.getIntent(key);
            if(intent == null) {
                intent = PointToPointIntent.builder()
                                            .appId(appId)
                                            .filteredIngressPoint(ingressPoint)
                                            .filteredEgressPoint(egressPoint)
                                            .key(key)
                                            .selector(selector)
                                            .treatment(treatment)
                                            .build();
                intentService.submit(intent);

                log.info("Intent `{}`, port `{}` => `{}`, port `{}` is submitted.",
                        cp.deviceId(), cp.port(), egressPoint.connectPoint().deviceId(), egressPoint.connectPoint().port());
            }

            // forward packet
            treatment = DefaultTrafficTreatment.builder().setOutput(egressPoint.connectPoint().port()).build();
            OutboundPacket packet = new DefaultOutboundPacket(egressPoint.connectPoint().deviceId(), treatment, context.inPacket().unparsed());
            packetService.emit(packet);
        }
    }
}
