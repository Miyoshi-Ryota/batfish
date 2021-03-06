package org.batfish.representation.aws;

import static org.batfish.datamodel.Interface.NULL_INTERFACE_NAME;
import static org.batfish.representation.aws.Utils.connectGatewayToVpc;
import static org.batfish.representation.aws.Utils.createPublicIpsRefBook;
import static org.batfish.representation.aws.Utils.publicIpAddressGroupName;
import static org.batfish.representation.aws.Utils.toStaticRoute;
import static org.batfish.representation.aws.Vpc.vrfNameForLink;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Collections;
import org.batfish.common.Warnings;
import org.batfish.common.topology.Layer1Edge;
import org.batfish.datamodel.Configuration;
import org.batfish.datamodel.ConfigurationFormat;
import org.batfish.datamodel.Interface;
import org.batfish.datamodel.Ip;
import org.batfish.datamodel.LinkLocalAddress;
import org.batfish.datamodel.Prefix;
import org.batfish.referencelibrary.AddressGroup;
import org.batfish.referencelibrary.GeneratedRefBookUtils;
import org.batfish.referencelibrary.GeneratedRefBookUtils.BookType;
import org.batfish.referencelibrary.ReferenceBook;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class UtilsTest {

  @Rule public ExpectedException _thrown = ExpectedException.none();

  @Test
  public void testCreatePublicIpsRefBook() {
    NetworkInterface networkInterface =
        new NetworkInterface(
            "interface",
            "subnet",
            "vpc",
            ImmutableList.of(),
            ImmutableList.of(
                new PrivateIpAddress(true, Ip.parse("10.10.10.10"), Ip.parse("5.5.5.5"))),
            "desc2",
            null);

    NetworkInterface networkInterface2 =
        new NetworkInterface(
            "interface2",
            "subnet",
            "vpc",
            ImmutableList.of(),
            ImmutableList.of(
                new PrivateIpAddress(true, Ip.parse("10.10.10.10"), Ip.parse("3.3.3.3")),
                new PrivateIpAddress(true, Ip.parse("10.10.10.10"), Ip.parse("4.4.4.4"))),
            "desc",
            null);

    NetworkInterface networkInterface0 =
        new NetworkInterface(
            "interface0",
            "subnet",
            "vpc",
            ImmutableList.of(),
            ImmutableList.of(new PrivateIpAddress(true, Ip.parse("10.10.10.10"), null)),
            "desc",
            null);

    Configuration cfgNode = new Configuration("cfg", ConfigurationFormat.AWS);
    String bookName = GeneratedRefBookUtils.getName(cfgNode.getHostname(), BookType.PublicIps);

    // book is not created if we don't have public IPs in the interface list
    createPublicIpsRefBook(Collections.singleton(networkInterface0), cfgNode);
    assertTrue(cfgNode.getGeneratedReferenceBooks().isEmpty());

    // the correct ref book is created from the three interfaces
    createPublicIpsRefBook(
        ImmutableList.of(networkInterface, networkInterface0, networkInterface2), cfgNode);
    assertThat(
        cfgNode.getGeneratedReferenceBooks().get(bookName),
        equalTo(
            ReferenceBook.builder(bookName)
                .setAddressGroups(
                    ImmutableList.of(
                        new AddressGroup(
                            ImmutableSortedSet.of("5.5.5.5"),
                            publicIpAddressGroupName(networkInterface)),
                        new AddressGroup(
                            ImmutableSortedSet.of("3.3.3.3", "4.4.4.4"),
                            publicIpAddressGroupName(networkInterface2))))
                .build()));

    // running it again should barf since we already have the reference book
    _thrown.expect(IllegalArgumentException.class);
    createPublicIpsRefBook(ImmutableList.of(networkInterface), cfgNode);
  }

  @Test
  public void testConnectGatewayToVpc() {
    Prefix vpcPrefix = Prefix.parse("10.10.0.0/16");
    Vpc vpc = new Vpc("vpc", ImmutableSet.of(vpcPrefix), ImmutableMap.of());
    Configuration vpcCfg = new Configuration(vpc.getId(), ConfigurationFormat.AWS);
    Configuration gatewayCfg = Utils.newAwsConfiguration("gateway", "aws");

    Region region =
        Region.builder("r1").setVpcs(ImmutableMap.of(vpcCfg.getHostname(), vpc)).build();

    ConvertedConfiguration awsConfiguration =
        new ConvertedConfiguration(ImmutableMap.of(vpcCfg.getHostname(), vpcCfg));

    Interface gatewayIface =
        connectGatewayToVpc(
            gatewayCfg.getHostname(),
            gatewayCfg,
            vpcCfg.getHostname(),
            awsConfiguration,
            region,
            new Warnings());

    // interfaces exist on both nodes
    assertThat(
        gatewayCfg.getAllInterfaces().keySet(),
        equalTo(ImmutableSet.of(Utils.interfaceNameToRemote(vpcCfg))));
    assertThat(
        vpcCfg.getAllInterfaces().keySet(),
        equalTo(ImmutableSet.of(Utils.interfaceNameToRemote(gatewayCfg))));

    Interface vpcIface = vpcCfg.getAllInterfaces().get(Utils.interfaceNameToRemote(gatewayCfg));

    // link exists
    assertThat(
        awsConfiguration.getLayer1Edges(),
        hasItems(
            new Layer1Edge(
                vpcCfg.getHostname(),
                vpcIface.getName(),
                gatewayCfg.getHostname(),
                gatewayIface.getName())));

    // static routes exist; also the gateway-specific VRF exists on the VPC
    assertThat(
        gatewayCfg.getDefaultVrf().getStaticRoutes(),
        equalTo(
            ImmutableSortedSet.of(
                toStaticRoute(
                    vpcPrefix,
                    gatewayIface.getName(),
                    ((LinkLocalAddress) vpcIface.getAddress()).getIp()))));
    assertThat(
        vpcCfg.getVrfs().get(vrfNameForLink(gatewayCfg.getHostname())).getStaticRoutes(),
        equalTo(
            ImmutableSortedSet.of(
                toStaticRoute(vpcPrefix, NULL_INTERFACE_NAME), // via Vpc.initializeVrf
                toStaticRoute(
                    Prefix.ZERO,
                    vpcIface.getName(),
                    ((LinkLocalAddress) gatewayIface.getAddress()).getIp()))));
  }
}
