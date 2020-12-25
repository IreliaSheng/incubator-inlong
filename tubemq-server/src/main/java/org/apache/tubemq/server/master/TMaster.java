/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.tubemq.server.master;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.commons.codec.binary.StringUtils;
import org.apache.commons.collections.CollectionUtils;
import org.apache.tubemq.corebase.TBaseConstants;
import org.apache.tubemq.corebase.TErrCodeConstants;
import org.apache.tubemq.corebase.TokenConstants;
import org.apache.tubemq.corebase.balance.ConsumerEvent;
import org.apache.tubemq.corebase.balance.EventStatus;
import org.apache.tubemq.corebase.balance.EventType;
import org.apache.tubemq.corebase.cluster.BrokerInfo;
import org.apache.tubemq.corebase.cluster.ConsumerInfo;
import org.apache.tubemq.corebase.cluster.NodeAddrInfo;
import org.apache.tubemq.corebase.cluster.Partition;
import org.apache.tubemq.corebase.cluster.ProducerInfo;
import org.apache.tubemq.corebase.cluster.SubscribeInfo;
import org.apache.tubemq.corebase.cluster.TopicInfo;
import org.apache.tubemq.corebase.config.TLSConfig;
import org.apache.tubemq.corebase.protobuf.generated.ClientMaster.CloseRequestB2M;
import org.apache.tubemq.corebase.protobuf.generated.ClientMaster.CloseRequestC2M;
import org.apache.tubemq.corebase.protobuf.generated.ClientMaster.CloseRequestP2M;
import org.apache.tubemq.corebase.protobuf.generated.ClientMaster.CloseResponseM2B;
import org.apache.tubemq.corebase.protobuf.generated.ClientMaster.CloseResponseM2C;
import org.apache.tubemq.corebase.protobuf.generated.ClientMaster.CloseResponseM2P;
import org.apache.tubemq.corebase.protobuf.generated.ClientMaster.EnableBrokerFunInfo;
import org.apache.tubemq.corebase.protobuf.generated.ClientMaster.EventProto;
import org.apache.tubemq.corebase.protobuf.generated.ClientMaster.HeartRequestB2M;
import org.apache.tubemq.corebase.protobuf.generated.ClientMaster.HeartRequestC2M;
import org.apache.tubemq.corebase.protobuf.generated.ClientMaster.HeartRequestP2M;
import org.apache.tubemq.corebase.protobuf.generated.ClientMaster.HeartResponseM2B;
import org.apache.tubemq.corebase.protobuf.generated.ClientMaster.HeartResponseM2C;
import org.apache.tubemq.corebase.protobuf.generated.ClientMaster.HeartResponseM2P;
import org.apache.tubemq.corebase.protobuf.generated.ClientMaster.MasterAuthorizedInfo;
import org.apache.tubemq.corebase.protobuf.generated.ClientMaster.MasterBrokerAuthorizedInfo;
import org.apache.tubemq.corebase.protobuf.generated.ClientMaster.RegisterRequestB2M;
import org.apache.tubemq.corebase.protobuf.generated.ClientMaster.RegisterRequestC2M;
import org.apache.tubemq.corebase.protobuf.generated.ClientMaster.RegisterRequestP2M;
import org.apache.tubemq.corebase.protobuf.generated.ClientMaster.RegisterResponseM2B;
import org.apache.tubemq.corebase.protobuf.generated.ClientMaster.RegisterResponseM2C;
import org.apache.tubemq.corebase.protobuf.generated.ClientMaster.RegisterResponseM2P;
import org.apache.tubemq.corebase.utils.ConcurrentHashSet;
import org.apache.tubemq.corebase.utils.DataConverterUtil;
import org.apache.tubemq.corebase.utils.TStringUtils;
import org.apache.tubemq.corebase.utils.ThreadUtils;
import org.apache.tubemq.corebase.utils.Tuple2;
import org.apache.tubemq.corerpc.RpcConfig;
import org.apache.tubemq.corerpc.RpcConstants;
import org.apache.tubemq.corerpc.RpcServiceFactory;
import org.apache.tubemq.corerpc.exception.StandbyException;
import org.apache.tubemq.corerpc.service.MasterService;
import org.apache.tubemq.server.Stoppable;
import org.apache.tubemq.server.common.TServerConstants;
import org.apache.tubemq.server.common.TStatusConstants;
import org.apache.tubemq.server.common.aaaserver.CertificateMasterHandler;
import org.apache.tubemq.server.common.aaaserver.CertifiedResult;
import org.apache.tubemq.server.common.aaaserver.SimpleCertificateMasterHandler;
import org.apache.tubemq.server.common.exception.HeartbeatException;
import org.apache.tubemq.server.common.heartbeat.HeartbeatManager;
import org.apache.tubemq.server.common.heartbeat.TimeoutInfo;
import org.apache.tubemq.server.common.heartbeat.TimeoutListener;
import org.apache.tubemq.server.common.offsetstorage.OffsetStorage;
import org.apache.tubemq.server.common.offsetstorage.ZkOffsetStorage;
import org.apache.tubemq.server.common.paramcheck.PBParameterUtils;
import org.apache.tubemq.server.common.paramcheck.ParamCheckResult;
import org.apache.tubemq.server.common.utils.HasThread;
import org.apache.tubemq.server.common.utils.RowLock;
import org.apache.tubemq.server.common.utils.Sleeper;
import org.apache.tubemq.server.master.balance.DefaultLoadBalancer;
import org.apache.tubemq.server.master.balance.LoadBalancer;
import org.apache.tubemq.server.master.bdbstore.DefaultBdbStoreService;
import org.apache.tubemq.server.master.bdbstore.bdbentitys.BdbBrokerConfEntity;
import org.apache.tubemq.server.master.bdbstore.bdbentitys.BdbGroupFlowCtrlEntity;
import org.apache.tubemq.server.master.nodemanage.nodebroker.BrokerConfManager;
import org.apache.tubemq.server.master.nodemanage.nodebroker.BrokerInfoHolder;
import org.apache.tubemq.server.master.nodemanage.nodebroker.BrokerSyncStatusInfo;
import org.apache.tubemq.server.master.nodemanage.nodebroker.TargetValidResult;
import org.apache.tubemq.server.master.nodemanage.nodebroker.TopicPSInfoManager;
import org.apache.tubemq.server.master.nodemanage.nodeconsumer.ConsumerBandInfo;
import org.apache.tubemq.server.master.nodemanage.nodeconsumer.ConsumerEventManager;
import org.apache.tubemq.server.master.nodemanage.nodeconsumer.ConsumerInfoHolder;
import org.apache.tubemq.server.master.nodemanage.nodeproducer.ProducerInfoHolder;
import org.apache.tubemq.server.master.utils.Chore;
import org.apache.tubemq.server.master.utils.SimpleVisitTokenManager;
import org.apache.tubemq.server.master.web.WebServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class TMaster extends HasThread implements MasterService, Stoppable {

    private static final Logger logger = LoggerFactory.getLogger(TMaster.class);
    private static final int MAX_BALANCE_DELAY_TIME = 10;

    private final ConcurrentHashMap<String/* consumerId */, Map<String/* topic */, Map<String, Partition>>>
            currentSubInfo = new ConcurrentHashMap<>();
    private final RpcServiceFactory rpcServiceFactory =     //rpc service factory
            new RpcServiceFactory();
    private final ConsumerEventManager consumerEventManager;    //consumer event manager
    private final TopicPSInfoManager topicPSInfoManager;        //topic publish/subscribe info manager
    private final ExecutorService executor;
    private final BrokerInfoHolder brokerHolder;                //broker holder
    private final ProducerInfoHolder producerHolder;            //producer holder
    private final ConsumerInfoHolder consumerHolder;            //consumer holder
    private final RowLock masterRowLock;                        //lock
    private final WebServer webServer;                          //web server
    private final LoadBalancer loadBalancer;                    //load balance
    private final MasterConfig masterConfig;                    //master config
    private final NodeAddrInfo masterAddInfo;                   //master address info
    private final HeartbeatManager heartbeatManager;            //heartbeat manager
    private final OffsetStorage zkOffsetStorage;                //zookeeper offset manager
    private final ShutdownHook shutdownHook;                    //shutdown hook
    private final DefaultBdbStoreService defaultBdbStoreService;        //bdb store service
    private final BrokerConfManager defaultBrokerConfManager;             //broker config manager
    private final CertificateMasterHandler serverAuthHandler;           //server auth handler
    private AtomicBoolean shutdownHooked = new AtomicBoolean(false);
    private AtomicLong idGenerator = new AtomicLong(0);     //id generator
    private volatile boolean stopped = false;                   //stop flag
    private Thread balancerChore;                               //balance chore
    private boolean initialized = false;
    private boolean startupBalance = true;
    private int balanceDelayTimes = 0;
    private AtomicInteger curBalanceParal = new AtomicInteger(0);
    private Sleeper stopSleeper = new Sleeper(1000, this);
    private SimpleVisitTokenManager visitTokenManager;

    /**
     * constructor
     *
     * @param masterConfig
     * @throws Exception
     */
    public TMaster(MasterConfig masterConfig) throws Exception {
        this.masterConfig = masterConfig;
        this.masterRowLock =
                new RowLock("Master-RowLock", this.masterConfig.getRowLockWaitDurMs());
        this.checkAndCreateBdbDataPath();
        this.masterAddInfo =
                new NodeAddrInfo(masterConfig.getHostName(), masterConfig.getPort());
        this.executor = Executors.newFixedThreadPool(this.masterConfig.getRebalanceParallel());
        this.visitTokenManager = new SimpleVisitTokenManager(this.masterConfig);
        this.serverAuthHandler = new SimpleCertificateMasterHandler(this.masterConfig);
        this.producerHolder = new ProducerInfoHolder();
        this.consumerHolder = new ConsumerInfoHolder();
        this.consumerEventManager = new ConsumerEventManager(consumerHolder);
        this.topicPSInfoManager = new TopicPSInfoManager();
        this.loadBalancer = new DefaultLoadBalancer();
        this.zkOffsetStorage = new ZkOffsetStorage(this.masterConfig.getZkConfig(),
                false, TBaseConstants.META_VALUE_UNDEFINED);
        this.heartbeatManager = new HeartbeatManager();
        heartbeatManager.regConsumerCheckBusiness(masterConfig.getConsumerHeartbeatTimeoutMs(),
                new TimeoutListener() {
                    @Override
                    public void onTimeout(String nodeId, TimeoutInfo nodeInfo) {
                        logger.info(new StringBuilder(512).append("[Consumer Timeout] ")
                                .append(nodeId).toString());
                        new ReleaseConsumer().run(nodeId);
                    }
                });
        heartbeatManager.regProducerCheckBusiness(masterConfig.getProducerHeartbeatTimeoutMs(),
                new TimeoutListener() {
                    @Override
                    public void onTimeout(final String nodeId, TimeoutInfo nodeInfo) {
                        logger.info(new StringBuilder(512).append("[Producer Timeout] ")
                                .append(nodeId).toString());
                        new ReleaseProducer().run(nodeId);
                    }
                });
        heartbeatManager.regBrokerCheckBusiness(masterConfig.getBrokerHeartbeatTimeoutMs(),
                new TimeoutListener() {
                    @Override
                    public void onTimeout(final String nodeId, TimeoutInfo nodeInfo) throws Exception {
                        logger.info(new StringBuilder(512).append("[Broker Timeout] ")
                                .append(nodeId).toString());
                        new ReleaseBroker().run(nodeId);
                    }
                });
        this.defaultBdbStoreService = new DefaultBdbStoreService(masterConfig, this);
        this.defaultBdbStoreService.start();
        this.defaultBrokerConfManager = new BrokerConfManager(this.defaultBdbStoreService);
        this.defaultBrokerConfManager.start();
        this.brokerHolder =
                new BrokerInfoHolder(this.masterConfig.getMaxAutoForbiddenCnt(),
                        this.defaultBrokerConfManager);
        RpcConfig rpcTcpConfig = new RpcConfig();
        rpcTcpConfig.put(RpcConstants.REQUEST_TIMEOUT,
                masterConfig.getRpcReadTimeoutMs());
        rpcTcpConfig.put(RpcConstants.NETTY_WRITE_HIGH_MARK,
                masterConfig.getNettyWriteBufferHighWaterMark());
        rpcTcpConfig.put(RpcConstants.NETTY_WRITE_LOW_MARK,
                masterConfig.getNettyWriteBufferLowWaterMark());
        rpcTcpConfig.put(RpcConstants.NETTY_TCP_SENDBUF,
                masterConfig.getSocketSendBuffer());
        rpcTcpConfig.put(RpcConstants.NETTY_TCP_RECEIVEBUF,
                masterConfig.getSocketRecvBuffer());
        rpcServiceFactory.publishService(MasterService.class,
                this, masterConfig.getPort(), rpcTcpConfig);
        if (masterConfig.isTlsEnable()) {
            TLSConfig tlsConfig = masterConfig.getTlsConfig();
            RpcConfig rpcTlsConfig = new RpcConfig();
            rpcTlsConfig.put(RpcConstants.TLS_OVER_TCP, true);
            rpcTlsConfig.put(RpcConstants.TLS_KEYSTORE_PATH,
                    tlsConfig.getTlsKeyStorePath());
            rpcTlsConfig.put(RpcConstants.TLS_KEYSTORE_PASSWORD,
                    tlsConfig.getTlsKeyStorePassword());
            rpcTlsConfig.put(RpcConstants.TLS_TWO_WAY_AUTHENTIC,
                    tlsConfig.isTlsTwoWayAuthEnable());
            if (tlsConfig.isTlsTwoWayAuthEnable()) {
                rpcTlsConfig.put(RpcConstants.TLS_TRUSTSTORE_PATH,
                        tlsConfig.getTlsTrustStorePath());
                rpcTlsConfig.put(RpcConstants.TLS_TRUSTSTORE_PASSWORD,
                        tlsConfig.getTlsTrustStorePassword());
            }
            rpcTlsConfig.put(RpcConstants.REQUEST_TIMEOUT,
                    masterConfig.getRpcReadTimeoutMs());
            rpcTlsConfig.put(RpcConstants.NETTY_WRITE_HIGH_MARK,
                    masterConfig.getNettyWriteBufferHighWaterMark());
            rpcTlsConfig.put(RpcConstants.NETTY_WRITE_LOW_MARK,
                    masterConfig.getNettyWriteBufferLowWaterMark());
            rpcTlsConfig.put(RpcConstants.NETTY_TCP_SENDBUF,
                    masterConfig.getSocketSendBuffer());
            rpcTlsConfig.put(RpcConstants.NETTY_TCP_RECEIVEBUF,
                    masterConfig.getSocketRecvBuffer());
            rpcServiceFactory.publishService(MasterService.class,
                    this, tlsConfig.getTlsPort(), rpcTlsConfig);
        }
        this.webServer = new WebServer(masterConfig, this);
        this.webServer.start();

        this.shutdownHook = new ShutdownHook();
        Runtime.getRuntime().addShutdownHook(this.shutdownHook);
    }

    /**
     * Get master config
     *
     * @return
     */
    public MasterConfig getMasterConfig() {
        return masterConfig;
    }

    /**
     * Get master topic manage
     *
     * @return
     */
    public BrokerConfManager getMasterTopicManager() {
        return this.defaultBrokerConfManager;
    }

    /**
     * Producer register request to master
     *
     * @param request
     * @param rmtAddress
     * @param overtls
     * @return register response
     * @throws Exception
     */
    @Override
    public RegisterResponseM2P producerRegisterP2M(RegisterRequestP2M request,
                                                   final String rmtAddress,
                                                   boolean overtls) throws Exception {
        final StringBuilder strBuffer = new StringBuilder(512);
        RegisterResponseM2P.Builder builder = RegisterResponseM2P.newBuilder();
        builder.setSuccess(false);
        builder.setBrokerCheckSum(-1);
        CertifiedResult certResult =
                serverAuthHandler.identityValidUserInfo(request.getAuthInfo(), true);
        if (!certResult.result) {
            builder.setErrCode(certResult.errCode);
            builder.setErrMsg(certResult.errInfo);
            return builder.build();
        }
        ParamCheckResult paramCheckResult =
                PBParameterUtils.checkClientId(request.getClientId(), strBuffer);
        if (!paramCheckResult.result) {
            builder.setErrCode(paramCheckResult.errCode);
            builder.setErrMsg(paramCheckResult.errMsg);
            return builder.build();
        }
        final String producerId = (String) paramCheckResult.checkData;
        paramCheckResult = PBParameterUtils.checkHostName(request.getHostName(), strBuffer);
        if (!paramCheckResult.result) {
            builder.setErrCode(paramCheckResult.errCode);
            builder.setErrMsg(paramCheckResult.errMsg);
            return builder.build();
        }
        final String hostName = (String) paramCheckResult.checkData;
        paramCheckResult =
                PBParameterUtils.checkProducerTopicList(request.getTopicListList(), strBuffer);
        if (!paramCheckResult.result) {
            builder.setErrCode(paramCheckResult.errCode);
            builder.setErrMsg(paramCheckResult.errMsg);
            return builder.build();
        }
        final Set<String> transTopicSet = (Set<String>) paramCheckResult.checkData;
        if (!request.hasBrokerCheckSum()) {
            builder.setErrCode(TErrCodeConstants.BAD_REQUEST);
            builder.setErrMsg("Request miss necessary brokerCheckSum field!");
            return builder.build();
        }
        checkNodeStatus(producerId, strBuffer);
        CertifiedResult authorizeResult =
                serverAuthHandler.validProducerAuthorizeInfo(
                        certResult.userName, transTopicSet, rmtAddress);
        if (!authorizeResult.result) {
            builder.setErrCode(authorizeResult.errCode);
            builder.setErrMsg(authorizeResult.errInfo);
            return builder.build();
        }
        String clientJdkVer = request.hasJdkVersion() ? request.getJdkVersion() : "";
        heartbeatManager.regProducerNode(producerId);
        producerHolder.setProducerInfo(producerId,
                new HashSet<>(transTopicSet), hostName, overtls);
        builder.setBrokerCheckSum(this.defaultBrokerConfManager.getBrokerInfoCheckSum());
        builder.addAllBrokerInfos(this.defaultBrokerConfManager.getBrokersMap(overtls).values());
        builder.setAuthorizedInfo(genAuthorizedInfo(certResult.authorizedToken, false).build());
        logger.info(strBuffer.append("[Producer Register] ").append(producerId)
            .append(", isOverTLS=").append(overtls)
            .append(", clientJDKVer=").append(clientJdkVer).toString());
        builder.setSuccess(true);
        builder.setErrCode(TErrCodeConstants.SUCCESS);
        builder.setErrMsg("OK!");
        return builder.build();
    }

    /**
     * Producer heartbeat request with master
     *
     * @param request
     * @param rmtAddress
     * @param overtls
     * @return heartbeat response
     * @throws Exception
     */
    @Override
    public HeartResponseM2P producerHeartbeatP2M(HeartRequestP2M request,
                                                 final String rmtAddress,
                                                 boolean overtls) throws Exception {
        final StringBuilder strBuffer = new StringBuilder(512);
        HeartResponseM2P.Builder builder = HeartResponseM2P.newBuilder();
        builder.setSuccess(false);
        builder.setBrokerCheckSum(-1);
        CertifiedResult certResult =
                serverAuthHandler.identityValidUserInfo(request.getAuthInfo(), true);
        if (!certResult.result) {
            builder.setErrCode(certResult.errCode);
            builder.setErrMsg(certResult.errInfo);
            return builder.build();
        }
        ParamCheckResult paramCheckResult =
                PBParameterUtils.checkClientId(request.getClientId(), strBuffer);
        if (!paramCheckResult.result) {
            builder.setErrCode(paramCheckResult.errCode);
            builder.setErrMsg(paramCheckResult.errMsg);
            return builder.build();
        }
        final String producerId = (String) paramCheckResult.checkData;
        paramCheckResult = PBParameterUtils.checkHostName(request.getHostName(), strBuffer);
        if (!paramCheckResult.result) {
            builder.setErrCode(paramCheckResult.errCode);
            builder.setErrMsg(paramCheckResult.errMsg);
            return builder.build();
        }
        final String hostName = (String) paramCheckResult.checkData;
        paramCheckResult =
                PBParameterUtils.checkProducerTopicList(request.getTopicListList(), strBuffer);
        if (!paramCheckResult.result) {
            builder.setErrCode(paramCheckResult.errCode);
            builder.setErrMsg(paramCheckResult.errMsg);
            return builder.build();
        }
        final Set<String> transTopicSet = (Set<String>) paramCheckResult.checkData;
        if (!request.hasBrokerCheckSum()) {
            builder.setErrCode(TErrCodeConstants.BAD_REQUEST);
            builder.setErrMsg("Request miss necessary brokerCheckSum field!");
            return builder.build();
        }
        final long inBrokerCheckSum = request.getBrokerCheckSum();
        checkNodeStatus(producerId, strBuffer);
        CertifiedResult authorizeResult =
                serverAuthHandler.validProducerAuthorizeInfo(
                        certResult.userName, transTopicSet, rmtAddress);
        if (!authorizeResult.result) {
            builder.setErrCode(authorizeResult.errCode);
            builder.setErrMsg(authorizeResult.errInfo);
            return builder.build();
        }
        try {
            heartbeatManager.updProducerNode(producerId);
        } catch (HeartbeatException e) {
            builder.setErrCode(TErrCodeConstants.HB_NO_NODE);
            builder.setErrMsg(e.getMessage());
            return builder.build();
        }
        topicPSInfoManager.addProducerTopicPubInfo(producerId, transTopicSet);
        producerHolder.updateProducerInfo(producerId,
                transTopicSet, hostName, overtls);
        Map<String, String> availTopicPartitions = getProducerTopicPartitionInfo(producerId);
        builder.addAllTopicInfos(availTopicPartitions.values());
        builder.setBrokerCheckSum(defaultBrokerConfManager.getBrokerInfoCheckSum());
        builder.setAuthorizedInfo(genAuthorizedInfo(certResult.authorizedToken, false).build());
        if (defaultBrokerConfManager.getBrokerInfoCheckSum() != inBrokerCheckSum) {
            builder.addAllBrokerInfos(defaultBrokerConfManager.getBrokersMap(overtls).values());
        }
        if (logger.isDebugEnabled()) {
            logger.debug(strBuffer.append("[Push Producer's available topic count:]")
                    .append(producerId).append(TokenConstants.LOG_SEG_SEP)
                    .append(availTopicPartitions.size()).toString());
        }
        builder.setSuccess(true);
        builder.setErrCode(TErrCodeConstants.SUCCESS);
        builder.setErrMsg("OK!");
        return builder.build();
    }

    /**
     * Producer close request with master
     *
     * @param request
     * @param rmtAddress
     * @param overtls
     * @return close response
     * @throws Exception
     */
    @Override
    public CloseResponseM2P producerCloseClientP2M(CloseRequestP2M request,
                                                   final String rmtAddress,
                                                   boolean overtls) throws Exception {
        final StringBuilder strBuffer = new StringBuilder(512);
        CloseResponseM2P.Builder builder = CloseResponseM2P.newBuilder();
        builder.setSuccess(false);
        CertifiedResult certResult =
                serverAuthHandler.identityValidUserInfo(request.getAuthInfo(), true);
        if (!certResult.result) {
            builder.setErrCode(certResult.errCode);
            builder.setErrMsg(certResult.errInfo);
            return builder.build();
        }
        ParamCheckResult paramCheckResult =
                PBParameterUtils.checkClientId(request.getClientId(), strBuffer);
        if (!paramCheckResult.result) {
            builder.setErrCode(paramCheckResult.errCode);
            builder.setErrMsg(paramCheckResult.errMsg);
            return builder.build();
        }
        final String producerId = (String) paramCheckResult.checkData;
        checkNodeStatus(producerId, strBuffer);
        new ReleaseProducer().run(producerId);
        heartbeatManager.unRegProducerNode(producerId);
        logger.info(strBuffer.append("Producer Closed")
                .append(producerId).append(", isOverTLS=").append(overtls).toString());
        builder.setSuccess(true);
        builder.setErrCode(TErrCodeConstants.SUCCESS);
        builder.setErrMsg("OK!");
        return builder.build();
    }

    /**
     * Consumer register request with master
     *
     * @param request
     * @param rmtAddress
     * @param overtls
     * @return register response
     * @throws Exception
     */
    @Override
    public RegisterResponseM2C consumerRegisterC2M(RegisterRequestC2M request,
                                                   final String rmtAddress,
                                                   boolean overtls) throws Exception {
        // #lizard forgives
        final StringBuilder strBuffer = new StringBuilder(512);
        RegisterResponseM2C.Builder builder = RegisterResponseM2C.newBuilder();
        builder.setSuccess(false);
        CertifiedResult certResult =
                serverAuthHandler.identityValidUserInfo(request.getAuthInfo(), false);
        if (!certResult.result) {
            builder.setErrCode(certResult.errCode);
            builder.setErrMsg(certResult.errInfo);
            return builder.build();
        }
        ParamCheckResult paramCheckResult =
                PBParameterUtils.checkClientId(request.getClientId(), strBuffer);
        if (!paramCheckResult.result) {
            builder.setErrCode(paramCheckResult.errCode);
            builder.setErrMsg(paramCheckResult.errMsg);
            return builder.build();
        }
        final String consumerId = (String) paramCheckResult.checkData;
        paramCheckResult = PBParameterUtils.checkHostName(request.getHostName(), strBuffer);
        if (!paramCheckResult.result) {
            builder.setErrCode(paramCheckResult.errCode);
            builder.setErrMsg(paramCheckResult.errMsg);
            return builder.build();
        }
        //final String hostName = (String) paramCheckResult.checkData;
        paramCheckResult = PBParameterUtils.checkGroupName(request.getGroupName(), strBuffer);
        if (!paramCheckResult.result) {
            builder.setErrCode(paramCheckResult.errCode);
            builder.setErrMsg(paramCheckResult.errMsg);
            return builder.build();
        }
        final String groupName = (String) paramCheckResult.checkData;
        paramCheckResult = PBParameterUtils.checkConsumerTopicList(request.getTopicListList(), strBuffer);
        if (!paramCheckResult.result) {
            builder.setErrCode(paramCheckResult.errCode);
            builder.setErrMsg(paramCheckResult.errMsg);
            return builder.build();
        }
        Set<String> reqTopicSet = (Set<String>) paramCheckResult.checkData;
        String requiredParts = request.hasRequiredPartition() ? request.getRequiredPartition() : "";
        boolean isReqConsumeBand = (request.hasRequireBound() && request.getRequireBound());
        String clientJdkVer = request.hasJdkVersion() ? request.getJdkVersion() : "";
        paramCheckResult = PBParameterUtils.checkConsumerOffsetSetInfo(isReqConsumeBand,
                reqTopicSet, requiredParts, strBuffer);
        if (!paramCheckResult.result) {
            builder.setErrCode(paramCheckResult.errCode);
            builder.setErrMsg(paramCheckResult.errMsg);
            return builder.build();
        }
        Map<String, Long> requiredPartMap = (Map<String, Long>) paramCheckResult.checkData;
        Map<String, TreeSet<String>> reqTopicConditions =
                DataConverterUtil.convertTopicConditions(request.getTopicConditionList());
        String sessionKey = request.hasSessionKey() ? request.getSessionKey() : "";
        long sessionTime = request.hasSessionTime()
                ? request.getSessionTime() : System.currentTimeMillis();
        int sourceCount = request.hasTotalCount()
                ? request.getTotalCount() : -1;
        int reqQryPriorityId = request.hasQryPriorityId()
                ? request.getQryPriorityId() : TBaseConstants.META_VALUE_UNDEFINED;
        boolean isSelectBig = (!request.hasSelectBig() || request.getSelectBig());
        ConsumerInfo inConsumerInfo =
                new ConsumerInfo(consumerId, overtls, groupName,
                        reqTopicSet, reqTopicConditions, isReqConsumeBand,
                        sessionKey, sessionTime, sourceCount, requiredPartMap);
        paramCheckResult =
                PBParameterUtils.checkConsumerInputInfo(inConsumerInfo,
                        masterConfig, defaultBrokerConfManager, topicPSInfoManager, strBuffer);
        if (!paramCheckResult.result) {
            builder.setErrCode(paramCheckResult.errCode);
            builder.setErrMsg(paramCheckResult.errMsg);
            return builder.build();
        }
        ConsumerInfo inConsumerInfo2 = (ConsumerInfo) paramCheckResult.checkData;
        checkNodeStatus(consumerId, strBuffer);
        CertifiedResult authorizeResult =
                serverAuthHandler.validConsumerAuthorizeInfo(certResult.userName,
                        groupName, reqTopicSet, reqTopicConditions, rmtAddress);
        if (!authorizeResult.result) {
            builder.setErrCode(authorizeResult.errCode);
            builder.setErrMsg(authorizeResult.errInfo);
            return builder.build();
        }
        // need removed for authorize center begin
        TargetValidResult validResult =
                this.defaultBrokerConfManager
                        .isConsumeTargetAuthorized(consumerId, groupName,
                                reqTopicSet, reqTopicConditions, strBuffer);
        if (!validResult.result) {
            if (strBuffer.length() > 0) {
                logger.warn(strBuffer.toString());
            }
            builder.setErrCode(validResult.errCode);
            builder.setErrMsg(validResult.errInfo);
            return builder.build();
        }
        // need removed for authorize center end
        Integer lid = null;
        try {
            lid = masterRowLock.getLock(null, StringUtils.getBytesUtf8(consumerId), true);
            ConsumerBandInfo consumerBandInfo = consumerHolder.getConsumerBandInfo(groupName);
            paramCheckResult =
                    PBParameterUtils.validConsumerExistInfo(inConsumerInfo2, isSelectBig, consumerBandInfo, strBuffer);
            if (!paramCheckResult.result) {
                builder.setErrCode(paramCheckResult.errCode);
                builder.setErrMsg(paramCheckResult.errMsg);
                return builder.build();
            }
            boolean registered = (consumerBandInfo != null) && (boolean) paramCheckResult.checkData;
            List<SubscribeInfo> subscribeList =
                    DataConverterUtil.convertSubInfo(request.getSubscribeInfoList());
            boolean isNotAllocated = true;
            if (CollectionUtils.isNotEmpty(subscribeList)
                    || ((request.hasNotAllocated() && !request.getNotAllocated()))) {
                isNotAllocated = false;
            }
            if ((consumerBandInfo != null && consumerBandInfo.getConsumerInfoList() == null) || !registered) {
                consumerHolder.addConsumer(inConsumerInfo2, isNotAllocated, isSelectBig);
            }
            for (String topic : reqTopicSet) {
                ConcurrentHashSet<String> groupSet =
                        topicPSInfoManager.getTopicSubInfo(topic);
                if (groupSet == null) {
                    groupSet = new ConcurrentHashSet<>();
                    topicPSInfoManager.setTopicSubInfo(topic, groupSet);
                }
                if (!groupSet.contains(groupName)) {
                    groupSet.add(groupName);
                }
            }
            if (CollectionUtils.isNotEmpty(subscribeList)) {
                Map<String, Map<String, Partition>> topicPartSubMap =
                        new HashMap<>();
                currentSubInfo.put(consumerId, topicPartSubMap);
                for (SubscribeInfo info : subscribeList) {
                    Map<String, Partition> partMap = topicPartSubMap.get(info.getTopic());
                    if (partMap == null) {
                        partMap = new HashMap<>();
                        topicPartSubMap.put(info.getTopic(), partMap);
                    }
                    partMap.put(info.getPartition().getPartitionKey(), info.getPartition());
                    logger.info(strBuffer.append("[SubInfo Report]")
                            .append(info.toString()).toString());
                    strBuffer.delete(0, strBuffer.length());
                }
            }
            heartbeatManager.regConsumerNode(getConsumerKey(groupName, consumerId));
        } catch (IOException e) {
            logger.warn("Failed to lock.", e);
        } finally {
            if (lid != null) {
                this.masterRowLock.releaseRowLock(lid);
            }
        }
        logger.info(strBuffer.append("[Consumer Register] ")
            .append(consumerId).append(", isOverTLS=").append(overtls)
            .append(", clientJDKVer=").append(clientJdkVer).toString());
        strBuffer.delete(0, strBuffer.length());
        if (request.hasDefFlowCheckId() || request.hasGroupFlowCheckId()) {
            builder.setSsdStoreId(TBaseConstants.META_VALUE_UNDEFINED);
            builder.setDefFlowCheckId(TBaseConstants.META_VALUE_UNDEFINED);
            builder.setGroupFlowCheckId(TBaseConstants.META_VALUE_UNDEFINED);
            builder.setQryPriorityId(TBaseConstants.META_VALUE_UNDEFINED);
            builder.setDefFlowControlInfo(" ");
            builder.setGroupFlowControlInfo(" ");
            BdbGroupFlowCtrlEntity defFlowCtrlEntity =
                    defaultBrokerConfManager.getBdbDefFlowCtrl();
            BdbGroupFlowCtrlEntity bdbGroupFlowCtrlEntity =
                    defaultBrokerConfManager.getBdbGroupFlowCtrl(groupName);
            if (defFlowCtrlEntity != null
                    && defFlowCtrlEntity.isValidStatus()) {
                builder.setDefFlowCheckId(defFlowCtrlEntity.getSerialId());
                if (request.getDefFlowCheckId() != defFlowCtrlEntity.getSerialId()) {
                    builder.setDefFlowControlInfo(defFlowCtrlEntity.getFlowCtrlInfo());
                }
            }
            if (bdbGroupFlowCtrlEntity != null
                    && bdbGroupFlowCtrlEntity.isValidStatus()) {
                builder.setGroupFlowCheckId(bdbGroupFlowCtrlEntity.getSerialId());
                builder.setQryPriorityId(bdbGroupFlowCtrlEntity.getQryPriorityId());
                if (request.getGroupFlowCheckId() != bdbGroupFlowCtrlEntity.getSerialId()) {
                    builder.setGroupFlowControlInfo(bdbGroupFlowCtrlEntity.getFlowCtrlInfo());
                }
            }
        }
        builder.setAuthorizedInfo(genAuthorizedInfo(certResult.authorizedToken, false));
        builder.setNotAllocated(consumerHolder.isNotAllocated(groupName));
        builder.setSuccess(true);
        builder.setErrCode(TErrCodeConstants.SUCCESS);
        builder.setErrMsg("OK!");
        return builder.build();
    }

    /**
     * Consumer heartbeat request with master
     *
     * @param request
     * @param rmtAddress
     * @param overtls
     * @return heartbeat response
     * @throws Throwable
     */
    @Override
    public HeartResponseM2C consumerHeartbeatC2M(HeartRequestC2M request,
                                                 final String rmtAddress,
                                                 boolean overtls) throws Throwable {
        // #lizard forgives
        final StringBuilder strBuffer = new StringBuilder(512);
        // response
        HeartResponseM2C.Builder builder = HeartResponseM2C.newBuilder();
        builder.setSuccess(false);
        // identity valid
        CertifiedResult certResult =
                serverAuthHandler.identityValidUserInfo(request.getAuthInfo(), false);
        if (!certResult.result) {
            builder.setErrCode(certResult.errCode);
            builder.setErrMsg(certResult.errInfo);
            return builder.build();
        }
        ParamCheckResult paramCheckResult =
                PBParameterUtils.checkClientId(request.getClientId(), strBuffer);
        if (!paramCheckResult.result) {
            builder.setErrCode(paramCheckResult.errCode);
            builder.setErrMsg(paramCheckResult.errMsg);
            return builder.build();
        }
        final String clientId = (String) paramCheckResult.checkData;
        paramCheckResult = PBParameterUtils.checkGroupName(request.getGroupName(), strBuffer);
        if (!paramCheckResult.result) {
            builder.setErrCode(paramCheckResult.errCode);
            builder.setErrMsg(paramCheckResult.errMsg);
            return builder.build();
        }
        final String groupName = (String) paramCheckResult.checkData;
        checkNodeStatus(clientId, strBuffer);
        ConsumerBandInfo consumerBandInfo = consumerHolder.getConsumerBandInfo(groupName);
        if (consumerBandInfo == null) {
            builder.setErrCode(TErrCodeConstants.HB_NO_NODE);
            builder.setErrMsg(strBuffer.append("Not found groupName ")
                    .append(groupName).append(" in holder!").toString());
            return builder.build();
        }
        // authorize check
        CertifiedResult authorizeResult =
                serverAuthHandler.validConsumerAuthorizeInfo(certResult.userName,
                        groupName, consumerBandInfo.getTopicSet(), consumerBandInfo.getTopicConditions(), rmtAddress);
        if (!authorizeResult.result) {
            builder.setErrCode(authorizeResult.errCode);
            builder.setErrMsg(authorizeResult.errInfo);
            return builder.build();
        }
        // heartbeat check
        try {
            heartbeatManager.updConsumerNode(getConsumerKey(groupName, clientId));
        } catch (HeartbeatException e) {
            builder.setErrCode(TErrCodeConstants.HB_NO_NODE);
            builder.setErrMsg(strBuffer
                    .append("Update consumer node exception:")
                    .append(e.getMessage()).toString());
            return builder.build();
        }
        //
        Map<String, Map<String, Partition>> topicPartSubList =
                currentSubInfo.get(clientId);
        if (topicPartSubList == null) {
            topicPartSubList = new HashMap<>();
            Map<String, Map<String, Partition>> tmpTopicPartSubList =
                    currentSubInfo.putIfAbsent(clientId, topicPartSubList);
            if (tmpTopicPartSubList != null) {
                topicPartSubList = tmpTopicPartSubList;
            }
        }
        long rebalanceId = request.hasEvent()
                ? request.getEvent().getRebalanceId() : TBaseConstants.META_VALUE_UNDEFINED;
        List<String> strSubInfoList = request.getSubscribeInfoList();
        if (request.getReportSubscribeInfo()) {
            List<SubscribeInfo> infoList = DataConverterUtil.convertSubInfo(strSubInfoList);
            if (!checkIfConsist(topicPartSubList, infoList)) {
                topicPartSubList.clear();
                for (SubscribeInfo info : infoList) {
                    Map<String, Partition> partMap =
                            topicPartSubList.get(info.getTopic());
                    if (partMap == null) {
                        partMap = new HashMap<>();
                        topicPartSubList.put(info.getTopic(), partMap);
                    }
                    Partition regPart =
                            new Partition(info.getPartition().getBroker(),
                                    info.getTopic(), info.getPartitionId());
                    partMap.put(regPart.getPartitionKey(), regPart);
                }
                if (rebalanceId <= 0) {
                    logger.warn(strBuffer.append("[Consistent Warn]").append(clientId)
                            .append(" sub info is not consistent with master.").toString());
                    strBuffer.delete(0, strBuffer.length());
                }
            }
        }
        //
        if (rebalanceId > 0) {
            ConsumerEvent processedEvent =
                    new ConsumerEvent(request.getEvent().getRebalanceId(),
                            EventType.valueOf(request.getEvent().getOpType()),
                            DataConverterUtil.convertSubInfo(request.getEvent().getSubscribeInfoList()),
                            EventStatus.valueOf(request.getEvent().getStatus()));
            strBuffer.append("[Event Processed] ");
            logger.info(processedEvent.toStrBuilder(strBuffer).toString());
            strBuffer.delete(0, strBuffer.length());
            try {
                consumerHolder.setAllocated(groupName);
                consumerEventManager.removeFirst(clientId);
            } catch (Throwable e) {
                logger.warn("Unknown exception for remove first event:", e);
            }
        }
        //
        ConsumerEvent event =
                consumerEventManager.peek(clientId);
        if (event != null
                && event.getStatus() != EventStatus.PROCESSING) {
            event.setStatus(EventStatus.PROCESSING);
            strBuffer.append("[Push Consumer Event]");
            logger.info(event.toStrBuilder(strBuffer).toString());
            strBuffer.delete(0, strBuffer.length());
            EventProto.Builder eventProtoBuilder =
                    EventProto.newBuilder();
            eventProtoBuilder.setRebalanceId(event.getRebalanceId());
            eventProtoBuilder.setOpType(event.getType().getValue());
            eventProtoBuilder.addAllSubscribeInfo(
                    DataConverterUtil.formatSubInfo(event.getSubscribeInfoList()));
            EventProto eventProto = eventProtoBuilder.build();
            builder.setEvent(eventProto);
        }
        if (request.hasDefFlowCheckId()
                || request.hasGroupFlowCheckId()) {
            builder.setSsdStoreId(TBaseConstants.META_VALUE_UNDEFINED);
            builder.setQryPriorityId(TBaseConstants.META_VALUE_UNDEFINED);
            builder.setDefFlowCheckId(TBaseConstants.META_VALUE_UNDEFINED);
            builder.setGroupFlowCheckId(TBaseConstants.META_VALUE_UNDEFINED);
            builder.setDefFlowControlInfo(" ");
            builder.setGroupFlowControlInfo(" ");
            BdbGroupFlowCtrlEntity defFlowCtrlEntity =
                    defaultBrokerConfManager.getBdbDefFlowCtrl();
            BdbGroupFlowCtrlEntity bdbGroupFlowCtrlEntity =
                    defaultBrokerConfManager.getBdbGroupFlowCtrl(groupName);
            if (defFlowCtrlEntity != null
                    && defFlowCtrlEntity.isValidStatus()) {
                builder.setDefFlowCheckId(defFlowCtrlEntity.getSerialId());
                if (request.getDefFlowCheckId() != defFlowCtrlEntity.getSerialId()) {
                    builder.setDefFlowControlInfo(defFlowCtrlEntity.getFlowCtrlInfo());
                }
            }
            if (bdbGroupFlowCtrlEntity != null
                    && bdbGroupFlowCtrlEntity.isValidStatus()) {
                builder.setGroupFlowCheckId(bdbGroupFlowCtrlEntity.getSerialId());
                builder.setQryPriorityId(bdbGroupFlowCtrlEntity.getQryPriorityId());
                if (request.getGroupFlowCheckId() != bdbGroupFlowCtrlEntity.getSerialId()) {
                    builder.setGroupFlowControlInfo(bdbGroupFlowCtrlEntity.getFlowCtrlInfo());
                }
            }
        }
        builder.setAuthorizedInfo(genAuthorizedInfo(certResult.authorizedToken, false));
        builder.setNotAllocated(consumerHolder.isNotAllocated(groupName));
        builder.setSuccess(true);
        builder.setErrCode(TErrCodeConstants.SUCCESS);
        builder.setErrMsg("OK!");
        return builder.build();
    }

    /**
     * Consumer close request with master
     *
     * @param request
     * @param rmtAddress
     * @param overtls
     * @return close response
     * @throws Exception
     */
    @Override
    public CloseResponseM2C consumerCloseClientC2M(CloseRequestC2M request,
                                                   final String rmtAddress,
                                                   boolean overtls) throws Exception {
        StringBuilder strBuffer = new StringBuilder(512);
        CloseResponseM2C.Builder builder = CloseResponseM2C.newBuilder();
        builder.setSuccess(false);
        CertifiedResult certResult =
                serverAuthHandler.identityValidUserInfo(request.getAuthInfo(), false);
        if (!certResult.result) {
            builder.setErrCode(certResult.errCode);
            builder.setErrMsg(certResult.errInfo);
            return builder.build();
        }
        ParamCheckResult paramCheckResult =
                PBParameterUtils.checkClientId(request.getClientId(), strBuffer);
        if (!paramCheckResult.result) {
            builder.setErrCode(paramCheckResult.errCode);
            builder.setErrMsg(paramCheckResult.errMsg);
            return builder.build();
        }
        final String clientId = (String) paramCheckResult.checkData;
        paramCheckResult = PBParameterUtils.checkGroupName(request.getGroupName(), strBuffer);
        if (!paramCheckResult.result) {
            builder.setErrCode(paramCheckResult.errCode);
            builder.setErrMsg(paramCheckResult.errMsg);
            return builder.build();
        }
        final String groupName = (String) paramCheckResult.checkData;
        checkNodeStatus(clientId, strBuffer);
        String nodeId = getConsumerKey(groupName, clientId);
        logger.info(strBuffer.append("[Consumer Closed]").append(nodeId)
                .append(", isOverTLS=").append(overtls).toString());
        new ReleaseConsumer().run(nodeId);
        heartbeatManager.unRegConsumerNode(nodeId);
        builder.setSuccess(true);
        builder.setErrCode(TErrCodeConstants.SUCCESS);
        builder.setErrMsg("OK!");
        return builder.build();
    }

    /**
     * Broker register request with master
     *
     * @param request
     * @param rmtAddress
     * @param overtls
     * @return register response
     * @throws Exception
     */
    @Override
    public RegisterResponseM2B brokerRegisterB2M(RegisterRequestB2M request,
                                                 final String rmtAddress,
                                                 boolean overtls) throws Exception {
        // #lizard forgives
        final StringBuilder strBuffer = new StringBuilder(512);
        RegisterResponseM2B.Builder builder = RegisterResponseM2B.newBuilder();
        builder.setSuccess(false);
        builder.setStopRead(false);
        builder.setStopWrite(false);
        builder.setTakeConfInfo(false);
        // auth
        CertifiedResult result =
                serverAuthHandler.identityValidBrokerInfo(request.getAuthInfo());
        if (!result.result) {
            builder.setErrCode(result.errCode);
            builder.setErrMsg(result.errInfo);
            return builder.build();
        }
        // get clientId and check valid
        ParamCheckResult paramCheckResult =
                PBParameterUtils.checkClientId(request.getClientId(), strBuffer);
        if (!paramCheckResult.result) {
            builder.setErrCode(paramCheckResult.errCode);
            builder.setErrMsg(paramCheckResult.errMsg);
            return builder.build();
        }
        final String clientId = (String) paramCheckResult.checkData;
        // check authority
        checkNodeStatus(clientId, strBuffer);
        // check broker validity
        //
        BrokerInfo brokerInfo =
                new BrokerInfo(clientId, request.getEnableTls(),
                        request.hasTlsPort() ? request.getTlsPort() : TBaseConstants.META_DEFAULT_BROKER_TLS_PORT);
        BdbBrokerConfEntity bdbBrokerConfEntity =
                defaultBrokerConfManager.getBrokerDefaultConfigStoreInfo(brokerInfo.getBrokerId());
        if (bdbBrokerConfEntity == null) {
            builder.setErrCode(TErrCodeConstants.BAD_REQUEST);
            builder.setErrMsg(strBuffer
                    .append("No broker configure info, please create first! the connecting client id is:")
                    .append(clientId).toString());
            return builder.build();
        }
        if ((!brokerInfo.getHost().equals(bdbBrokerConfEntity.getBrokerIp()))
                || (brokerInfo.getPort() != bdbBrokerConfEntity.getBrokerPort())) {
            builder.setErrCode(TErrCodeConstants.BAD_REQUEST);
            builder.setErrMsg(strBuffer
                    .append("Inconsistent broker configure,please confirm first! the connecting client id is:")
                    .append(clientId).append(", the configure's broker address by brokerId is:")
                    .append(bdbBrokerConfEntity.getBrokerIdAndAddress()).toString());
            return builder.build();
        }
        int confTLSPort = bdbBrokerConfEntity.getBrokerTLSPort();
        if (confTLSPort != brokerInfo.getTlsPort()) {
            builder.setErrCode(TErrCodeConstants.BAD_REQUEST);
            builder.setErrMsg(strBuffer
                    .append("Inconsistent TLS configure,please confirm first! the connecting client id is:")
                    .append(clientId).append(", the configured TLS port is:")
                    .append(confTLSPort).append(", the broker reported TLS port is ")
                    .append(brokerInfo.getTlsPort()).toString());
            return builder.build();
        }
        if (bdbBrokerConfEntity.getManageStatus() == TStatusConstants.STATUS_MANAGE_APPLY) {
            builder.setErrCode(TErrCodeConstants.BAD_REQUEST);
            builder.setErrMsg(strBuffer
                    .append("Broker's configure not online, please online configure first! " +
                            "the connecting client id is:")
                    .append(clientId).toString());
            return builder.build();
        }
        // get optional filed
        boolean needFastStart = false;
        final long reFlowCtrlId = request.hasFlowCheckId()
                ? request.getFlowCheckId() : TBaseConstants.META_VALUE_UNDEFINED;
        final int qryPriorityId = request.hasQryPriorityId()
                ? request.getQryPriorityId() : TBaseConstants.META_VALUE_UNDEFINED;
        ConcurrentHashMap<Integer, BrokerSyncStatusInfo> brokerSyncStatusMap =
                this.defaultBrokerConfManager.getBrokerRunSyncManageMap();
        // update broker status
        List<String> brokerTopicSetConfInfo =
                this.defaultBrokerConfManager.getBrokerTopicStrConfigInfo(bdbBrokerConfEntity);
        BrokerSyncStatusInfo brokerStatusInfo =
                new BrokerSyncStatusInfo(bdbBrokerConfEntity, brokerTopicSetConfInfo);
        brokerSyncStatusMap.put(bdbBrokerConfEntity.getBrokerId(), brokerStatusInfo);
        brokerStatusInfo.updateCurrBrokerConfInfo(bdbBrokerConfEntity.getManageStatus(),
                bdbBrokerConfEntity.isConfDataUpdated(), bdbBrokerConfEntity.isBrokerLoaded(),
                bdbBrokerConfEntity.getBrokerDefaultConfInfo(), brokerTopicSetConfInfo, false);
        if (brokerTopicSetConfInfo.isEmpty()) {
            needFastStart = true;
        }
        brokerStatusInfo.setFastStart(needFastStart);
        // set broker report info
        if (request.getCurBrokerConfId() <= 0) {
            brokerStatusInfo.setBrokerReportInfo(true, request.getCurBrokerConfId(),
                    request.getConfCheckSumId(), true, request.getBrokerDefaultConfInfo(),
                    request.getBrokerTopicSetConfInfoList(), true, request.getBrokerOnline(), overtls);
        } else {
            brokerStatusInfo.setBrokerReportInfo(true,
                    brokerStatusInfo.getLastPushBrokerConfId(),
                    brokerStatusInfo.getLastPushBrokerCheckSumId(), true,
                    bdbBrokerConfEntity.getBrokerDefaultConfInfo(), brokerTopicSetConfInfo, true,
                    request.getBrokerOnline(), overtls);
        }
        this.defaultBrokerConfManager.removeBrokerRunTopicInfoMap(brokerInfo.getBrokerId());
        brokerHolder.setBrokerInfo(brokerInfo.getBrokerId(), brokerInfo);
        heartbeatManager.regBrokerNode(String.valueOf(brokerInfo.getBrokerId()));
        logger.info(strBuffer.append("[Broker Register] ").append(clientId)
            .append(" report, configureId=").append(request.getCurBrokerConfId())
            .append(",readStatusRpt=").append(request.getReadStatusRpt())
            .append(",writeStatusRpt=").append(request.getWriteStatusRpt())
            .append(",isTlsEnable=").append(brokerInfo.isEnableTLS())
            .append(",TLSport=").append(brokerInfo.getTlsPort())
            .append(",FlowCtrlId=").append(reFlowCtrlId)
            .append(",qryPriorityId=").append(qryPriorityId)
            .append(",checksumId=").append(request.getConfCheckSumId()).toString());
        strBuffer.delete(0, strBuffer.length());
        if (request.getCurBrokerConfId() > 0) {
            processBrokerReportConfigureInfo(brokerInfo, strBuffer);
        }
        // response
        builder.setSuccess(true);
        builder.setErrCode(TErrCodeConstants.SUCCESS);
        builder.setErrMsg("OK!");
        // begin:  deprecated when brokers version equal to current master version
        builder.setAuthorizedInfo(genAuthorizedInfo(null, true));
        // end deprecated
        builder.setBrokerAuthorizedInfo(genBrokerAuthorizedInfo(null));
        EnableBrokerFunInfo.Builder enableInfo = EnableBrokerFunInfo.newBuilder();
        enableInfo.setEnableVisitTokenCheck(masterConfig.isStartVisitTokenCheck());
        enableInfo.setEnableProduceAuthenticate(masterConfig.isStartProduceAuthenticate());
        enableInfo.setEnableProduceAuthorize(masterConfig.isStartProduceAuthorize());
        enableInfo.setEnableConsumeAuthenticate(masterConfig.isStartConsumeAuthenticate());
        enableInfo.setEnableConsumeAuthorize(masterConfig.isStartConsumeAuthorize());
        builder.setEnableBrokerInfo(enableInfo);
        builder.setTakeConfInfo(true);
        builder.setCurBrokerConfId(brokerStatusInfo.getLastPushBrokerConfId());
        builder.setConfCheckSumId(brokerStatusInfo.getLastPushBrokerCheckSumId());
        builder.setBrokerDefaultConfInfo(brokerStatusInfo.getLastPushBrokerDefaultConfInfo());
        builder.addAllBrokerTopicSetConfInfo(brokerStatusInfo.getLastPushBrokerTopicSetConfInfo());
        builder.setSsdStoreId(TBaseConstants.META_VALUE_UNDEFINED);
        if (request.hasFlowCheckId()) {
            BdbGroupFlowCtrlEntity bdbGroupFlowCtrlEntity =
                    defaultBrokerConfManager.getBdbDefFlowCtrl();
            if (bdbGroupFlowCtrlEntity == null) {
                builder.setFlowCheckId(TBaseConstants.META_VALUE_UNDEFINED);
                builder.setQryPriorityId(TBaseConstants.META_VALUE_UNDEFINED);
                if (request.getFlowCheckId() != TBaseConstants.META_VALUE_UNDEFINED) {
                    builder.setFlowControlInfo(" ");
                }
            } else {
                builder.setQryPriorityId(bdbGroupFlowCtrlEntity.getQryPriorityId());
                builder.setFlowCheckId(bdbGroupFlowCtrlEntity.getSerialId());
                if (reFlowCtrlId != bdbGroupFlowCtrlEntity.getSerialId()) {
                    if (bdbGroupFlowCtrlEntity.isValidStatus()) {
                        builder.setFlowControlInfo(bdbGroupFlowCtrlEntity.getFlowCtrlInfo());
                    } else {
                        builder.setFlowControlInfo(" ");
                    }
                }
            }
        }
        logger.info(strBuffer.append("[TMaster sync] push broker configure: brokerId = ")
            .append(brokerStatusInfo.getBrokerId())
            .append(",configureId=").append(brokerStatusInfo.getLastPushBrokerConfId())
            .append(",stopWrite=").append(builder.getStopWrite())
            .append(",stopRead=").append(builder.getStopRead())
            .append(",checksumId=").append(brokerStatusInfo.getLastPushBrokerCheckSumId())
            .append(",default configure is ").append(brokerStatusInfo.getLastPushBrokerDefaultConfInfo())
            .append(",topic configure is ").append(brokerStatusInfo.getLastPushBrokerTopicSetConfInfo())
            .toString());
        strBuffer.delete(0, strBuffer.length());
        logger.info(strBuffer.append("[Broker Register] ").append(clientId)
                .append(", isOverTLS=").append(overtls).toString());
        return builder.build();
    }

    /**
     * Broker heartbeat request with master
     *
     * @param request
     * @param rmtAddress
     * @param overtls
     * @return heartbeat response
     * @throws Exception
     */
    @Override
    public HeartResponseM2B brokerHeartbeatB2M(HeartRequestB2M request,
                                               final String rmtAddress,
                                               boolean overtls) throws Exception {
        // #lizard forgives
        final StringBuilder strBuffer = new StringBuilder(512);
        // set response field
        HeartResponseM2B.Builder builder = HeartResponseM2B.newBuilder();
        builder.setSuccess(false);
        builder.setStopRead(false);
        builder.setStopWrite(false);
        builder.setNeedReportData(true);
        builder.setTakeConfInfo(false);
        builder.setTakeRemoveTopicInfo(false);
        builder.setFlowCheckId(TBaseConstants.META_VALUE_UNDEFINED);
        builder.setQryPriorityId(TBaseConstants.META_VALUE_UNDEFINED);
        builder.setCurBrokerConfId(TBaseConstants.META_VALUE_UNDEFINED);
        builder.setConfCheckSumId(TBaseConstants.META_VALUE_UNDEFINED);
        // identity broker info
        CertifiedResult result =
                serverAuthHandler.identityValidBrokerInfo(request.getAuthInfo());
        if (!result.result) {
            builder.setErrCode(result.errCode);
            builder.setErrMsg(result.errInfo);
            return builder.build();
        }
        ParamCheckResult paramCheckResult =
                PBParameterUtils.checkBrokerId(request.getBrokerId(), strBuffer);
        if (!paramCheckResult.result) {
            builder.setErrCode(paramCheckResult.errCode);
            builder.setErrMsg(paramCheckResult.errMsg);
            return builder.build();
        }
        final String brokerId = (String) paramCheckResult.checkData;
        checkNodeStatus(brokerId, strBuffer);
        BrokerInfo brokerInfo = brokerHolder.getBrokerInfo(Integer.parseInt(brokerId));
        if (brokerInfo == null) {
            builder.setErrCode(TErrCodeConstants.HB_NO_NODE);
            builder.setErrMsg(strBuffer
                    .append("Please register broker first! the connecting client id is:")
                    .append(brokerId).toString());
            return builder.build();
        }
        BdbBrokerConfEntity bdbBrokerConfEntity =
                defaultBrokerConfManager.getBrokerDefaultConfigStoreInfo(brokerInfo.getBrokerId());
        if (bdbBrokerConfEntity == null) {
            builder.setErrCode(TErrCodeConstants.BAD_REQUEST);
            builder.setErrMsg(strBuffer
                    .append("No broker configure info, please create first! the connecting client id is:")
                    .append(brokerInfo.toString()).toString());
            return builder.build();
        }
        if (bdbBrokerConfEntity.getManageStatus() == TStatusConstants.STATUS_MANAGE_APPLY) {
            builder.setErrCode(TErrCodeConstants.BAD_REQUEST);
            builder.setErrMsg(strBuffer
                    .append("Broker's configure not online, " +
                            "please online configure first! the connecting client id is:")
                    .append(brokerInfo.toString()).toString());
            return builder.build();
        }
        ConcurrentHashMap<Integer, BrokerSyncStatusInfo> brokerSyncStatusMap =
                this.defaultBrokerConfManager.getBrokerRunSyncManageMap();
        BrokerSyncStatusInfo brokerSyncStatusInfo =
                brokerSyncStatusMap.get(brokerInfo.getBrokerId());
        if (brokerSyncStatusInfo == null) {
            builder.setErrCode(TErrCodeConstants.BAD_REQUEST);
            builder.setErrMsg(strBuffer
                    .append("Not found Broker run status info,please register first! the connecting client id is:")
                    .append(brokerInfo.toString()).toString());
            return builder.build();
        }
        // update heartbeat
        try {
            heartbeatManager.updBrokerNode(brokerId);
        } catch (HeartbeatException e) {
            builder.setErrCode(TErrCodeConstants.HB_NO_NODE);
            builder.setErrMsg(e.getMessage());
            return builder.build();
        }
        // update broker status
        brokerSyncStatusInfo.setBrokerReportInfo(false, request.getCurBrokerConfId(),
                request.getConfCheckSumId(), request.getTakeConfInfo(),
                request.getBrokerDefaultConfInfo(), request.getBrokerTopicSetConfInfoList(),
                true, request.getBrokerOnline(), overtls);
        processBrokerReportConfigureInfo(brokerInfo, strBuffer);
        if (request.getTakeRemovedTopicInfo()) {
            List<String> removedTopics = request.getRemovedTopicsInfoList();
            logger.info(strBuffer.append("[Broker Report] receive broker confirmed removed topic list is ")
                    .append(removedTopics.toString()).toString());
            strBuffer.delete(0, strBuffer.length());
            this.defaultBrokerConfManager
                    .clearRemovedTopicEntityInfo(bdbBrokerConfEntity.getBrokerId(), removedTopics);
        }
        brokerHolder.updateBrokerReportStatus(brokerInfo.getBrokerId(),
                request.getReadStatusRpt(), request.getWriteStatusRpt());
        long reFlowCtrlId = request.hasFlowCheckId()
                ? request.getFlowCheckId() : TBaseConstants.META_VALUE_UNDEFINED;
        int qryPriorityId = request.hasQryPriorityId()
                ? request.getQryPriorityId() : TBaseConstants.META_VALUE_UNDEFINED;
        if (request.getTakeConfInfo()) {
            strBuffer.append("[Broker Report] heartbeat report: brokerId=")
                .append(request.getBrokerId()).append(", configureId=")
                .append(request.getCurBrokerConfId())
                .append(",readStatusRpt=").append(request.getReadStatusRpt())
                .append(",writeStatusRpt=").append(request.getWriteStatusRpt())
                .append(",checksumId=").append(request.getConfCheckSumId())
                .append(",hasFlowCheckId=").append(request.hasFlowCheckId())
                .append(",reFlowCtrlId=").append(reFlowCtrlId)
                .append(",qryPriorityId=").append(qryPriorityId)
                .append(",brokerOnline=").append(request.getBrokerOnline())
                .append(",default broker configure is ").append(request.getBrokerDefaultConfInfo())
                .append(",broker topic configure is ").append(request.getBrokerTopicSetConfInfoList())
                .append(",current brokerSyncStatusInfo is ");
            logger.info(brokerSyncStatusInfo.toJsonString(strBuffer, true).toString());
            strBuffer.delete(0, strBuffer.length());
        }
        // create response
        builder.setNeedReportData(brokerSyncStatusInfo.needReportData());
        builder.setCurBrokerConfId(brokerSyncStatusInfo.getLastPushBrokerConfId());
        builder.setConfCheckSumId(brokerSyncStatusInfo.getLastPushBrokerCheckSumId());
        builder.setSsdStoreId(TBaseConstants.META_VALUE_UNDEFINED);
        if (request.hasFlowCheckId()) {
            BdbGroupFlowCtrlEntity bdbGroupFlowCtrlEntity =
                    defaultBrokerConfManager.getBdbDefFlowCtrl();
            if (bdbGroupFlowCtrlEntity == null) {
                builder.setFlowCheckId(TBaseConstants.META_VALUE_UNDEFINED);
                builder.setQryPriorityId(TBaseConstants.META_VALUE_UNDEFINED);
                if (request.getFlowCheckId() != TBaseConstants.META_VALUE_UNDEFINED) {
                    builder.setFlowControlInfo(" ");
                }
            } else {
                builder.setFlowCheckId(bdbGroupFlowCtrlEntity.getSerialId());
                builder.setQryPriorityId(bdbGroupFlowCtrlEntity.getQryPriorityId());
                if (reFlowCtrlId != bdbGroupFlowCtrlEntity.getSerialId()) {
                    if (bdbGroupFlowCtrlEntity.isValidStatus()) {
                        builder.setFlowControlInfo(bdbGroupFlowCtrlEntity.getFlowCtrlInfo());
                    } else {
                        builder.setFlowControlInfo(" ");
                    }
                }
            }
        }
        brokerHolder.setBrokerHeartBeatReqStatus(brokerInfo.getBrokerId(), builder);
        builder.setTakeRemoveTopicInfo(true);
        builder.addAllRemoveTopicConfInfo(defaultBrokerConfManager
                .getBrokerRemovedTopicStrConfigInfo(bdbBrokerConfEntity));
        if (brokerSyncStatusInfo.needSyncConfDataToBroker()) {
            builder.setTakeConfInfo(true);
            builder.setBrokerDefaultConfInfo(brokerSyncStatusInfo
                    .getLastPushBrokerDefaultConfInfo());
            builder.addAllBrokerTopicSetConfInfo(brokerSyncStatusInfo
                    .getLastPushBrokerTopicSetConfInfo());
            logger.info(strBuffer
                .append("[Broker Report] heartbeat sync topic config: brokerId=")
                .append(brokerId).append(", configureId=")
                .append(brokerSyncStatusInfo.getLastPushBrokerConfId())
                .append(",set flowCtrlId=").append(builder.getFlowCheckId())
                .append(",stopWrite=").append(builder.getStopWrite())
                .append(",stopRead=").append(builder.getStopRead())
                .append(",qryPriorityId=").append(builder.getQryPriorityId())
                .append(",checksumId=").append(brokerSyncStatusInfo.getLastPushBrokerCheckSumId())
                .append(",default configure is ")
                .append(brokerSyncStatusInfo.getLastPushBrokerDefaultConfInfo())
                .append(",topic configure is ")
                .append(brokerSyncStatusInfo.getLastPushBrokerTopicSetConfInfo())
                .toString());
        }
        // begin:  deprecated when brokers version equal to current master version
        builder.setAuthorizedInfo(genAuthorizedInfo(null, true));
        // end deprecated
        builder.setBrokerAuthorizedInfo(genBrokerAuthorizedInfo(null));
        builder.setSuccess(true);
        builder.setErrCode(TErrCodeConstants.SUCCESS);
        builder.setErrMsg("OK!");
        return builder.build();
    }

    /**
     * Broker close request with master
     *
     * @param request
     * @param rmtAddress
     * @param overtls
     * @return close response
     * @throws Throwable
     */
    @Override
    public CloseResponseM2B brokerCloseClientB2M(CloseRequestB2M request,
                                                 final String rmtAddress,
                                                 boolean overtls) throws Throwable {
        StringBuilder strBuffer = new StringBuilder(512);
        CloseResponseM2B.Builder builder = CloseResponseM2B.newBuilder();
        builder.setSuccess(false);
        CertifiedResult result =
                serverAuthHandler.identityValidBrokerInfo(request.getAuthInfo());
        if (!result.result) {
            builder.setErrCode(result.errCode);
            builder.setErrMsg(result.errInfo);
            return builder.build();
        }
        ParamCheckResult paramCheckResult =
                PBParameterUtils.checkBrokerId(request.getBrokerId(), strBuffer);
        if (!paramCheckResult.result) {
            builder.setErrCode(paramCheckResult.errCode);
            builder.setErrMsg(paramCheckResult.errMsg);
            return builder.build();
        }
        final String brokerId = (String) paramCheckResult.checkData;
        checkNodeStatus(brokerId, strBuffer);
        logger.info(strBuffer.append("[Broker Closed]").append(brokerId)
                .append(", isOverTLS=").append(overtls).toString());
        new ReleaseBroker().run(brokerId);
        heartbeatManager.unRegBrokerNode(request.getBrokerId());
        builder.setSuccess(true);
        builder.setErrCode(TErrCodeConstants.SUCCESS);
        builder.setErrMsg("OK!");
        return builder.build();
    }

    /**
     * Generate consumer id
     *
     * @param group
     * @param consumerId
     * @return
     */
    private String getConsumerKey(String group, String consumerId) {
        return new StringBuilder(512).append(consumerId)
                .append("@").append(group).toString();
    }

    /**
     * Get producer topic partition info
     *
     * @param producerId
     * @return
     */
    private Map<String, String> getProducerTopicPartitionInfo(String producerId) {
        Map<String, String> topicPartStrMap = new HashMap<>();
        ProducerInfo producerInfo = producerHolder.getProducerInfo(producerId);
        if (producerInfo == null) {
            return topicPartStrMap;
        }
        Set<String> producerInfoTopicSet =
                producerInfo.getTopicSet();
        if ((producerInfoTopicSet == null)
                || (producerInfoTopicSet.isEmpty())) {
            return topicPartStrMap;
        }
        Map<String, StringBuilder> topicPartStrBuilderMap =
                new HashMap<>();
        for (String topic : producerInfoTopicSet) {
            if (topic == null) {
                continue;
            }
            ConcurrentHashMap<BrokerInfo, TopicInfo> topicInfoMap =
                    topicPSInfoManager.getBrokerPubInfo(topic);
            if (topicInfoMap == null) {
                continue;
            }
            for (Map.Entry<BrokerInfo, TopicInfo> entry : topicInfoMap.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) {
                    continue;
                }
                if (entry.getValue().isAcceptPublish()) {
                    StringBuilder tmpValue = topicPartStrBuilderMap.get(topic);
                    if (tmpValue == null) {
                        StringBuilder strBuffer =
                                new StringBuilder(512).append(topic)
                                        .append(TokenConstants.SEGMENT_SEP)
                                        .append(entry.getValue().getSimpleValue());
                        topicPartStrBuilderMap.put(topic, strBuffer);
                    } else {
                        tmpValue.append(TokenConstants.ARRAY_SEP)
                                .append(entry.getValue().getSimpleValue());
                    }
                }
            }
        }
        for (Map.Entry<String, StringBuilder> entry : topicPartStrBuilderMap.entrySet()) {
            if (entry.getValue() != null) {
                topicPartStrMap.put(entry.getKey(), entry.getValue().toString());
            }
        }
        topicPartStrBuilderMap.clear();
        return topicPartStrMap;
    }

    /**
     * Update topics
     *
     * @param brokerInfo
     * @param strBuffer
     * @param curTopicInfoMap
     * @param newTopicInfoMap
     * @param requirePartUpdate
     * @param requireAcceptPublish
     * @param requireAcceptSubscribe
     */
    private void updateTopics(BrokerInfo brokerInfo, final StringBuilder strBuffer,
                              Map<String/* topicName */, TopicInfo> curTopicInfoMap,
                              Map<String/* topicName */, TopicInfo> newTopicInfoMap,
                              boolean requirePartUpdate, boolean requireAcceptPublish,
                              boolean requireAcceptSubscribe) {
        List<TopicInfo> needAddTopicList = new ArrayList<>();
        for (Map.Entry<String, TopicInfo> entry : newTopicInfoMap.entrySet()) {
            TopicInfo newTopicInfo = entry.getValue();
            TopicInfo oldTopicInfo = null;
            if (curTopicInfoMap != null) {
                oldTopicInfo = curTopicInfoMap.get(entry.getKey());
            }
            if (oldTopicInfo == null
                    || oldTopicInfo.getPartitionNum() != newTopicInfo.getPartitionNum()
                    || oldTopicInfo.getTopicStoreNum() != newTopicInfo.getTopicStoreNum()
                    || oldTopicInfo.isAcceptPublish() != newTopicInfo.isAcceptPublish()
                    || oldTopicInfo.isAcceptSubscribe() != newTopicInfo.isAcceptSubscribe()) {
                if (requirePartUpdate) {
                    if (!requireAcceptPublish) {
                        newTopicInfo.setAcceptPublish(false);
                    }
                    if (!requireAcceptSubscribe) {
                        newTopicInfo.setAcceptSubscribe(false);
                    }
                }
                needAddTopicList.add(newTopicInfo);
            }
        }
        updateTopicsInternal(brokerInfo, needAddTopicList, EventType.CONNECT);
        logger.info(strBuffer.append("[addedTopicConfigMap] broker:")
                .append(brokerInfo.toString()).append(" add topicInfo list:")
                .append(needAddTopicList).toString());
        strBuffer.delete(0, strBuffer.length());
    }

    /**
     * Delete topics
     *
     * @param brokerInfo
     * @param strBuffer
     * @param curTopicInfoMap
     * @param newTopicInfoMap
     */
    private void deleteTopics(BrokerInfo brokerInfo, final StringBuilder strBuffer,
                              Map<String/* topicName */, TopicInfo> curTopicInfoMap,
                              Map<String/* topicName */, TopicInfo> newTopicInfoMap) {
        List<TopicInfo> needRmvTopicList = new ArrayList<>();
        if (curTopicInfoMap != null) {
            for (Map.Entry<String, TopicInfo> entry : curTopicInfoMap.entrySet()) {
                if (newTopicInfoMap.get(entry.getKey()) == null) {
                    needRmvTopicList.add(entry.getValue());
                }
            }
        }
        updateTopicsInternal(brokerInfo, needRmvTopicList, EventType.DISCONNECT);
        logger.info(strBuffer.append("[removedTopicConfigMap] broker:")
                .append(brokerInfo.toString()).append(" removed topicInfo list:")
                .append(needRmvTopicList).toString());
        strBuffer.delete(0, strBuffer.length());
    }

    /**
     * Process broker report configure info
     *
     * @param brokerInfo
     * @param strBuffer
     */
    private void processBrokerReportConfigureInfo(BrokerInfo brokerInfo,
                                                  final StringBuilder strBuffer) {
        // #lizard forgives
        BrokerSyncStatusInfo brokerSyncStatusInfo =
                this.defaultBrokerConfManager.getBrokerRunSyncStatusInfo(brokerInfo.getBrokerId());
        if (brokerSyncStatusInfo == null) {
            logger.error(strBuffer
                    .append("Fail to find broker run manage configure! broker is ")
                    .append(brokerInfo.toString()).toString());
            strBuffer.delete(0, strBuffer.length());
            return;
        }
        boolean requireAcceptPublish = false;
        boolean requireAcceptSubscribe = false;
        boolean requirePartUpdate = false;
        boolean requireSyncClient = false;
        int brokerManageStatus = brokerSyncStatusInfo.getBrokerManageStatus();
        int brokerRunStatus = brokerSyncStatusInfo.getBrokerRunStatus();
        long subStepOpTimeInMills = brokerSyncStatusInfo.getSubStepOpTimeInMills();
        boolean isBrokerRegister = brokerSyncStatusInfo.isBrokerRegister();
        boolean isBrokerOnline = brokerSyncStatusInfo.isBrokerOnline();
        if (!isBrokerRegister) {
            return;
        }
        if (brokerManageStatus == TStatusConstants.STATUS_MANAGE_ONLINE) {
            if (isBrokerOnline) {
                if (brokerRunStatus == TStatusConstants.STATUS_SERVICE_UNDEFINED) {
                    return;
                } else if (brokerRunStatus == TStatusConstants.STATUS_SERVICE_TOONLINE_WAIT_REGISTER
                        || brokerRunStatus == TStatusConstants.STATUS_SERVICE_TOONLINE_WAIT_ONLINE) {
                    brokerSyncStatusInfo.setBrokerRunStatus(TStatusConstants.STATUS_SERVICE_TOONLINE_ONLY_READ);
                    requireAcceptSubscribe = true;
                    requireSyncClient = true;
                } else if (brokerRunStatus == TStatusConstants.STATUS_SERVICE_TOONLINE_ONLY_READ) {
                    if ((brokerSyncStatusInfo.isBrokerConfChanged())
                            || (!brokerSyncStatusInfo.isBrokerLoaded())) {
                        long waitTime =
                                brokerSyncStatusInfo.isFastStart() ? masterConfig.getStepChgWaitPeriodMs()
                                        : masterConfig.getOnlineOnlyReadToRWPeriodMs();
                        if ((System.currentTimeMillis() - subStepOpTimeInMills) > waitTime) {
                            brokerSyncStatusInfo
                                    .setBrokerRunStatus(TStatusConstants.STATUS_SERVICE_TOONLINE_READ_AND_WRITE);
                            requireAcceptPublish = true;
                            requireAcceptSubscribe = true;
                            requireSyncClient = true;
                        }
                    } else {
                        brokerSyncStatusInfo
                                .setBrokerRunStatus(TStatusConstants.STATUS_SERVICE_TOONLINE_READ_AND_WRITE);
                        requireAcceptPublish = true;
                        requireAcceptSubscribe = true;
                        requireSyncClient = true;
                    }
                } else if (brokerRunStatus == TStatusConstants.STATUS_SERVICE_TOONLINE_READ_AND_WRITE) {
                    long waitTime =
                            brokerSyncStatusInfo.isFastStart() ? 0 : masterConfig.getStepChgWaitPeriodMs();
                    if ((System.currentTimeMillis() - subStepOpTimeInMills) > waitTime) {
                        brokerSyncStatusInfo.setBrokerRunStatus(TStatusConstants.STATUS_SERVICE_UNDEFINED);
                        brokerSyncStatusInfo.setFastStart(true);
                        requireAcceptPublish = true;
                        requireAcceptSubscribe = true;
                        requireSyncClient = true;
                        if ((brokerSyncStatusInfo.isBrokerConfChanged())
                                || (!brokerSyncStatusInfo.isBrokerLoaded())) {
                            this.defaultBrokerConfManager
                                    .updateBrokerConfChanged(brokerInfo.getBrokerId(), false, true);
                        }
                    }
                } else if (brokerRunStatus == TStatusConstants.STATUS_SERVICE_TOONLINE_PART_WAIT_REGISTER) {
                    brokerSyncStatusInfo
                            .setBrokerRunStatus(TStatusConstants.STATUS_SERVICE_TOONLINE_PART_WAIT_ONLINE);
                    requireAcceptSubscribe = true;
                    requireSyncClient = true;
                    requirePartUpdate = true;
                } else if (brokerRunStatus == TStatusConstants.STATUS_SERVICE_TOONLINE_PART_WAIT_ONLINE) {
                    brokerSyncStatusInfo
                            .setBrokerRunStatus(TStatusConstants.STATUS_SERVICE_TOONLINE_PART_ONLY_READ);
                    requireAcceptSubscribe = true;
                    requireSyncClient = true;
                    requirePartUpdate = true;
                } else if (brokerRunStatus == TStatusConstants.STATUS_SERVICE_TOONLINE_PART_ONLY_READ) {
                    if ((brokerSyncStatusInfo.isBrokerConfChanged())
                            || (!brokerSyncStatusInfo.isBrokerLoaded())) {
                        long waitTime =
                                brokerSyncStatusInfo.isFastStart()
                                        ? 0 : masterConfig.getOnlineOnlyReadToRWPeriodMs();
                        if ((System.currentTimeMillis() - subStepOpTimeInMills) > waitTime) {
                            brokerSyncStatusInfo
                                    .setBrokerRunStatus(TStatusConstants.STATUS_SERVICE_TOONLINE_READ_AND_WRITE);
                            requireAcceptPublish = true;
                            requireAcceptSubscribe = true;
                            requireSyncClient = true;
                            requirePartUpdate = true;
                        }
                    } else {
                        brokerSyncStatusInfo
                                .setBrokerRunStatus(TStatusConstants.STATUS_SERVICE_TOONLINE_READ_AND_WRITE);
                        requireAcceptPublish = true;
                        requireAcceptSubscribe = true;
                        requireSyncClient = true;
                        requirePartUpdate = true;
                    }
                }
            } else {
                if (brokerRunStatus != TStatusConstants.STATUS_SERVICE_TOONLINE_WAIT_ONLINE) {
                    brokerSyncStatusInfo
                            .setBrokerRunStatus(TStatusConstants.STATUS_SERVICE_TOONLINE_WAIT_ONLINE);
                    requireSyncClient = true;
                }
            }
        } else {
            if (!isBrokerOnline
                    || brokerRunStatus == TStatusConstants.STATUS_SERVICE_UNDEFINED) {
                return;
            }
            if (brokerManageStatus == TStatusConstants.STATUS_MANAGE_ONLINE_NOT_WRITE) {
                if (brokerRunStatus == TStatusConstants.STATUS_SERVICE_TOONLINE_ONLY_READ) {
                    long waitTime =
                            brokerSyncStatusInfo.isFastStart() ? 0 : masterConfig.getStepChgWaitPeriodMs();
                    if ((System.currentTimeMillis() - subStepOpTimeInMills) > waitTime) {
                        brokerSyncStatusInfo
                                .setBrokerRunStatus(TStatusConstants.STATUS_SERVICE_TOOFFLINE_WAIT_REBALANCE);
                        requireAcceptSubscribe = true;
                        requireSyncClient = true;
                    }
                } else if (brokerRunStatus == TStatusConstants.STATUS_SERVICE_TOOFFLINE_WAIT_REBALANCE) {
                    long waitTime =
                            brokerSyncStatusInfo.isFastStart()
                                    ? masterConfig.getStepChgWaitPeriodMs()
                                    : masterConfig.getOfflineOnlyReadToRWPeriodMs();
                    if ((System.currentTimeMillis() - subStepOpTimeInMills) > waitTime) {
                        brokerSyncStatusInfo.setBrokerRunStatus(TStatusConstants.STATUS_SERVICE_UNDEFINED);
                        brokerSyncStatusInfo.setFastStart(true);
                        requireAcceptSubscribe = true;
                        requireSyncClient = true;
                    }
                }
            } else if (brokerManageStatus == TStatusConstants.STATUS_MANAGE_ONLINE_NOT_READ) {
                if (brokerRunStatus == TStatusConstants.STATUS_SERVICE_TOONLINE_ONLY_WRITE) {
                    long waitTime =
                            brokerSyncStatusInfo.isFastStart() ? 0 : masterConfig.getStepChgWaitPeriodMs();
                    if ((System.currentTimeMillis() - subStepOpTimeInMills) > waitTime) {
                        brokerSyncStatusInfo
                                .setBrokerRunStatus(TStatusConstants.STATUS_SERVICE_TOOFFLINE_WAIT_REBALANCE);
                        requireAcceptPublish = true;
                        requireSyncClient = true;
                    }
                } else if (brokerRunStatus == TStatusConstants.STATUS_SERVICE_TOOFFLINE_WAIT_REBALANCE) {
                    long waitTime =
                            brokerSyncStatusInfo.isFastStart() ? masterConfig.getStepChgWaitPeriodMs()
                                    : masterConfig.getOfflineOnlyReadToRWPeriodMs();
                    if ((System.currentTimeMillis() - subStepOpTimeInMills) > waitTime) {
                        brokerSyncStatusInfo.setBrokerRunStatus(TStatusConstants.STATUS_SERVICE_UNDEFINED);
                        brokerSyncStatusInfo.setFastStart(true);
                        requireAcceptPublish = true;
                        requireSyncClient = true;
                    }
                }
            } else if (brokerManageStatus == TStatusConstants.STATUS_MANAGE_OFFLINE) {
                if (brokerRunStatus == TStatusConstants.STATUS_SERVICE_TOOFFLINE_NOT_WRITE) {
                    brokerSyncStatusInfo
                            .setBrokerRunStatus(TStatusConstants.STATUS_SERVICE_TOOFFLINE_NOT_READ_WRITE);
                    requireAcceptSubscribe = true;
                    requireSyncClient = true;
                } else if (brokerRunStatus == TStatusConstants.STATUS_SERVICE_TOOFFLINE_NOT_READ_WRITE) {
                    long waitTime =
                            brokerSyncStatusInfo.isFastStart() ? 0 : masterConfig.getStepChgWaitPeriodMs();
                    if ((System.currentTimeMillis() - subStepOpTimeInMills) > waitTime) {
                        brokerSyncStatusInfo
                                .setBrokerRunStatus(TStatusConstants.STATUS_SERVICE_TOOFFLINE_WAIT_REBALANCE);
                        requireSyncClient = true;
                    }
                } else if (brokerRunStatus == TStatusConstants.STATUS_SERVICE_TOOFFLINE_WAIT_REBALANCE) {
                    long waitTime = brokerSyncStatusInfo.isFastStart()
                            ? masterConfig.getStepChgWaitPeriodMs()
                            : masterConfig.getOfflineOnlyReadToRWPeriodMs();
                    if ((System.currentTimeMillis() - subStepOpTimeInMills) > waitTime) {
                        brokerSyncStatusInfo.setBrokerRunStatus(TStatusConstants.STATUS_SERVICE_UNDEFINED);
                        brokerSyncStatusInfo.setFastStart(true);
                        requireSyncClient = true;
                    }
                }
            }
        }

        if (requireSyncClient) {
            updateTopicInfoToClient(brokerInfo, requirePartUpdate,
                    requireAcceptPublish, requireAcceptSubscribe, strBuffer);
        }
    }

    /**
     * Update topic info to client
     *
     * @param brokerInfo
     * @param requirePartUpdate
     * @param requireAcceptPublish
     * @param requireAcceptSubscribe
     * @param strBuffer
     */
    private void updateTopicInfoToClient(BrokerInfo brokerInfo,
                                         boolean requirePartUpdate,
                                         boolean requireAcceptPublish,
                                         boolean requireAcceptSubscribe,
                                         final StringBuilder strBuffer) {
        // #lizard forgives
        // check broker status
        BrokerSyncStatusInfo brokerSyncStatusInfo =
                this.defaultBrokerConfManager.getBrokerRunSyncStatusInfo(brokerInfo.getBrokerId());
        if (brokerSyncStatusInfo == null) {
            logger.error(strBuffer
                    .append("Fail to find broker run manage configure, not update topic info! broker is ")
                    .append(brokerInfo.toString()).toString());
            strBuffer.delete(0, strBuffer.length());
            return;
        }
        // get broker config and then generate topic status record
        String brokerDefaultConfInfo = brokerSyncStatusInfo.getReportedBrokerDefaultConfInfo();
        int brokerManageStatusId = brokerSyncStatusInfo.getBrokerManageStatus();
        if (TStringUtils.isBlank(brokerDefaultConfInfo)) {
            return;
        }
        // get broker status and topic default config
        boolean acceptPublish = false;
        boolean acceptSubscribe = false;
        if (brokerManageStatusId >= TStatusConstants.STATUS_MANAGE_ONLINE) {
            if (brokerManageStatusId == TStatusConstants.STATUS_MANAGE_ONLINE) {
                acceptPublish = true;
                acceptSubscribe = true;
            } else if (brokerManageStatusId == TStatusConstants.STATUS_MANAGE_ONLINE_NOT_WRITE) {
                acceptSubscribe = true;
            } else if (brokerManageStatusId == TStatusConstants.STATUS_MANAGE_ONLINE_NOT_READ) {
                acceptPublish = true;
            }
        }
        List<String> brokerTopicSetConfInfo =
                brokerSyncStatusInfo.getReportedBrokerTopicSetConfInfo();
        String[] brokerDefaultConfInfoArr = brokerDefaultConfInfo.split(TokenConstants.ATTR_SEP);
        int numPartitions = Integer.parseInt(brokerDefaultConfInfoArr[0]);
        boolean cfgAcceptPublish = Boolean.parseBoolean(brokerDefaultConfInfoArr[1]);
        boolean cfgAcceptSubscribe = Boolean.parseBoolean(brokerDefaultConfInfoArr[2]);
        int numTopicStores = 1;
        if (brokerDefaultConfInfoArr.length > 7) {
            if (!TStringUtils.isBlank(brokerDefaultConfInfoArr[7])) {
                numTopicStores = Integer.parseInt(brokerDefaultConfInfoArr[7]);
            }
        }
        int unFlushDataHold = TServerConstants.CFG_DEFAULT_DATA_UNFLUSH_HOLD;
        if (brokerDefaultConfInfoArr.length > 8) {
            if (!TStringUtils.isBlank(brokerDefaultConfInfoArr[8])) {
                unFlushDataHold = Integer.parseInt(brokerDefaultConfInfoArr[8]);
            }
        }
        ConcurrentHashMap<String/* topic */, TopicInfo> newTopicInfoMap =
                new ConcurrentHashMap<>();
        // according to broker status and default config, topic config, make up current status record
        for (String strTopicConfInfo : brokerTopicSetConfInfo) {
            if (TStringUtils.isBlank(strTopicConfInfo)) {
                continue;
            }
            String[] topicConfInfoArr =
                    strTopicConfInfo.split(TokenConstants.ATTR_SEP);
            final String tmpTopic = topicConfInfoArr[0];
            int tmpPartNum = numPartitions;
            if (!TStringUtils.isBlank(topicConfInfoArr[1])) {
                tmpPartNum = Integer.parseInt(topicConfInfoArr[1]);
            }
            boolean tmpAcceptPublish = cfgAcceptPublish;
            if (!TStringUtils.isBlank(topicConfInfoArr[2])) {
                tmpAcceptPublish = Boolean.parseBoolean(topicConfInfoArr[2]);
            }
            if (!acceptPublish) {
                tmpAcceptPublish = acceptPublish;
            } else {
                if (!requirePartUpdate) {
                    if (!requireAcceptPublish) {
                        tmpAcceptPublish = false;
                    }
                }
            }
            int tmpNumTopicStores = numTopicStores;
            if (!TStringUtils.isBlank(topicConfInfoArr[8])) {
                tmpNumTopicStores = Integer.parseInt(topicConfInfoArr[8]);
                tmpNumTopicStores = tmpNumTopicStores > 0 ? tmpNumTopicStores : numTopicStores;
            }
            boolean tmpAcceptSubscribe = cfgAcceptSubscribe;
            if (!TStringUtils.isBlank(topicConfInfoArr[3])) {
                tmpAcceptSubscribe = Boolean.parseBoolean(topicConfInfoArr[3]);
            }
            if (!acceptSubscribe) {
                tmpAcceptSubscribe = acceptSubscribe;
            } else {
                if (!requirePartUpdate) {
                    if (!requireAcceptSubscribe) {
                        tmpAcceptSubscribe = false;
                    }
                }
            }
            newTopicInfoMap.put(tmpTopic, new TopicInfo(brokerInfo, tmpTopic,
                    tmpPartNum, tmpNumTopicStores, tmpAcceptPublish, tmpAcceptSubscribe));
        }

        ConcurrentHashMap<String/* topicName */, TopicInfo> oldTopicInfoMap =
                defaultBrokerConfManager.getBrokerRunTopicInfoMap(brokerInfo.getBrokerId());
        deleteTopics(brokerInfo, strBuffer, oldTopicInfoMap, newTopicInfoMap);
        updateTopics(brokerInfo, strBuffer, oldTopicInfoMap, newTopicInfoMap,
                requirePartUpdate, requireAcceptPublish, requireAcceptSubscribe);
        defaultBrokerConfManager.updateBrokerRunTopicInfoMap(brokerInfo.getBrokerId(), newTopicInfoMap);
    }

    /**
     * Update topic internal
     *
     * @param broker
     * @param topicList
     * @param type
     */
    private void updateTopicsInternal(BrokerInfo broker,
                                      List<TopicInfo> topicList,
                                      EventType type) {
        List<TopicInfo> cloneTopicList = new ArrayList<>();
        for (TopicInfo topicInfo : topicList) {
            cloneTopicList.add(topicInfo.clone());
        }
        for (TopicInfo topicInfo : cloneTopicList) {
            Integer lid = null;
            try {
                lid = this.masterRowLock.getLock(null, StringUtils.getBytesUtf8(topicInfo.getTopic()), true);
                ConcurrentHashMap<BrokerInfo, TopicInfo> topicInfoMap =
                        topicPSInfoManager.getBrokerPubInfo(topicInfo.getTopic());
                if (topicInfoMap == null) {
                    topicInfoMap = new ConcurrentHashMap<>();
                    topicPSInfoManager.setBrokerPubInfo(topicInfo.getTopic(), topicInfoMap);
                }
                if (EventType.CONNECT == type) {
                    topicInfoMap.put(broker, topicInfo);
                } else {
                    topicInfoMap.remove(broker);
                }
            } catch (IOException e) {
                logger.error("Get lock error!", e);
            } finally {
                if (lid != null) {
                    this.masterRowLock.releaseRowLock(lid);
                }
            }
        }
    }

    @Override
    public void run() {
        try {
            if (!this.stopped) {
                Thread.sleep(masterConfig.getFirstBalanceDelayAfterStartMs());
                this.balancerChore = startBalancerChore(this);
                initialized = true;
                while (!this.stopped) {
                    stopSleeper.sleep();
                }
            }
        } catch (Throwable e) {
            stopChores();
        }
    }

    public boolean isInitialized() {
        return initialized;
    }


    /**
     * Load balance
     */
    private void balance(final TMaster tMaster) {
        final StringBuilder strBuffer = new StringBuilder(512);
        final long rebalanceId = idGenerator.incrementAndGet();
        if (defaultBdbStoreService != null) {
            logger.info(strBuffer.append("[Rebalance Start] ").append(rebalanceId)
                    .append(", isMaster=").append(defaultBdbStoreService.isMaster())
                    .append(", isPrimaryNodeActive=")
                    .append(defaultBdbStoreService.isPrimaryNodeActive()).toString());
        } else {
            logger.info(strBuffer.append("[Rebalance Start] ").append(rebalanceId)
                    .append(", BDB service is null isMaster= false, isPrimaryNodeActive=false").toString());
        }
        strBuffer.delete(0, strBuffer.length());
        int curDoingTasks = this.curBalanceParal.get();
        if (curDoingTasks > 0) {
            logger.info(strBuffer.append("[Rebalance End] ").append(rebalanceId)
                    .append(", the previous rebalance has ")
                    .append(curDoingTasks).append(" task(s) in progress!").toString());
            return;
        }
        final boolean isStartBalance = startupBalance;
        List<String> groupsNeedToBalance = isStartBalance ?
                consumerHolder.getAllGroup() : getNeedToBalanceGroupList(strBuffer);
        strBuffer.delete(0, strBuffer.length());
        if (!groupsNeedToBalance.isEmpty()) {
            // set parallel rebalance signal
            curBalanceParal.set(masterConfig.getRebalanceParallel());
            // calculate process count
            int unitNum = (groupsNeedToBalance.size() + masterConfig.getRebalanceParallel() - 1)
                    / masterConfig.getRebalanceParallel();
            // start processer to do reblance;
            int startIndex = 0;
            int endIndex = 0;
            for (int i = 0; i < masterConfig.getRebalanceParallel(); i++) {
                // get groups need to rebalance
                startIndex = Math.min((i) * unitNum, groupsNeedToBalance.size());
                endIndex = Math.min((i + 1) * unitNum, groupsNeedToBalance.size());
                final List<String> subGroups = groupsNeedToBalance.subList(startIndex, endIndex);
                // execute rebalance
                this.executor.execute(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            if (subGroups.isEmpty()) {
                                return;
                            }
                            // first process reset rebalance task;
                            try {
                                tMaster.processResetbalance(rebalanceId,
                                        isStartBalance, subGroups);
                            } catch (Throwable e) {
                                logger.warn("[Rebalance processor] Error during reset-reb", e);
                            }
                            if (tMaster.isStopped()) {
                                return;
                            }
                            // second process normal rebalance task;
                            try {
                                tMaster.processRebalance(rebalanceId,
                                        isStartBalance, subGroups);
                            } catch (Throwable e) {
                                logger.warn("[Rebalance processor] Error during normal-reb", e);
                            }
                        } catch (Throwable e) {
                            logger.warn("[Rebalance processor] Error during process", e);
                        } finally {
                            curBalanceParal.decrementAndGet();
                        }
                    }
                });
            }
        }
        startupBalance = false;
        logger.info(strBuffer.append("[Rebalance End] ").append(rebalanceId).toString());
    }

    // process unReset group rebalance
    public void processRebalance(long rebalanceId, boolean isFirstReb, List<String> groups) {
        // #lizard forgives
        Map<String, Map<String, List<Partition>>> finalSubInfoMap = null;
        final StringBuilder strBuffer = new StringBuilder(512);
        // choose different load balance strategy
        if (isFirstReb) {
            finalSubInfoMap = this.loadBalancer.bukAssign(consumerHolder,
                    topicPSInfoManager, groups, defaultBrokerConfManager,
                    masterConfig.getMaxGroupBrokerConsumeRate(), strBuffer);
        } else {
            finalSubInfoMap = this.loadBalancer.balanceCluster(currentSubInfo,
                    consumerHolder, brokerHolder, topicPSInfoManager, groups,
                    defaultBrokerConfManager, masterConfig.getMaxGroupBrokerConsumeRate(),
                    strBuffer);
        }
        // allocate partitions to consumers
        for (Map.Entry<String, Map<String, List<Partition>>> entry : finalSubInfoMap.entrySet()) {
            String consumerId = entry.getKey();
            if (consumerId == null) {
                continue;
            }
            Tuple2<String, ConsumerInfo> tupleInfo =
                    consumerHolder.getConsumeTupleInfo(consumerId);
            if (tupleInfo == null
                    || tupleInfo.f0 == null
                    || tupleInfo.f1 == null) {
                continue;
            }
            List<String> blackTopicList =
                    this.defaultBrokerConfManager.getBdbBlackTopicList(tupleInfo.f0);
            Map<String, List<Partition>> topicSubPartMap = entry.getValue();
            List<SubscribeInfo> deletedSubInfoList = new ArrayList<>();
            List<SubscribeInfo> addedSubInfoList = new ArrayList<>();
            for (Map.Entry<String, List<Partition>> topicEntry : topicSubPartMap.entrySet()) {
                String topic = topicEntry.getKey();
                List<Partition> finalPartList = topicEntry.getValue();
                Map<String, Partition> currentPartMap = null;
                Map<String, Map<String, Partition>> curTopicSubInfoMap =
                        currentSubInfo.get(consumerId);
                if (curTopicSubInfoMap == null || curTopicSubInfoMap.get(topic) == null) {
                    currentPartMap = new HashMap<>();
                } else {
                    currentPartMap = curTopicSubInfoMap.get(topic);
                    if (currentPartMap == null) {
                        currentPartMap = new HashMap<>();
                    }
                }
                if (tupleInfo.f1.isOverTLS()) {
                    for (Partition currentPart : currentPartMap.values()) {
                        if (!blackTopicList.contains(currentPart.getTopic())) {
                            boolean found = false;
                            for (Partition newPart : finalPartList) {
                                if (newPart.getPartitionFullStr(true)
                                        .equals(currentPart.getPartitionFullStr(true))) {
                                    found = true;
                                    break;
                                }
                            }
                            if (found) {
                                continue;
                            }
                        }
                        deletedSubInfoList
                                .add(new SubscribeInfo(consumerId, tupleInfo.f0,
                                        tupleInfo.f1.isOverTLS(), currentPart));
                    }
                    for (Partition finalPart : finalPartList) {
                        if (!blackTopicList.contains(finalPart.getTopic())) {
                            boolean found = false;
                            for (Partition curPart : currentPartMap.values()) {
                                if (finalPart.getPartitionFullStr(true)
                                        .equals(curPart.getPartitionFullStr(true))) {
                                    found = true;
                                    break;
                                }
                            }
                            if (found) {
                                continue;
                            }
                            addedSubInfoList.add(new SubscribeInfo(consumerId,
                                    tupleInfo.f0, true, finalPart));
                        }
                    }
                } else {
                    for (Partition currentPart : currentPartMap.values()) {
                        if ((blackTopicList.contains(currentPart.getTopic()))
                                || (!finalPartList.contains(currentPart))) {
                            deletedSubInfoList.add(new SubscribeInfo(consumerId,
                                    tupleInfo.f0, false, currentPart));
                        }
                    }
                    for (Partition finalPart : finalPartList) {
                        if ((currentPartMap.get(finalPart.getPartitionKey()) == null)
                                && (!blackTopicList.contains(finalPart.getTopic()))) {
                            addedSubInfoList.add(new SubscribeInfo(consumerId,
                                    tupleInfo.f0, false, finalPart));
                        }
                    }
                }
            }
            if (deletedSubInfoList.size() > 0) {
                EventType opType =
                        addedSubInfoList.size() > 0
                                ? EventType.DISCONNECT : EventType.ONLY_DISCONNECT;
                consumerEventManager
                        .addDisconnectEvent(consumerId,
                                new ConsumerEvent(rebalanceId, opType,
                                        deletedSubInfoList, EventStatus.TODO));
                for (SubscribeInfo info : deletedSubInfoList) {
                    logger.info(strBuffer.append("[Disconnect]")
                            .append(info.toString()).toString());
                    strBuffer.delete(0, strBuffer.length());
                }
            }
            if (addedSubInfoList.size() > 0) {
                EventType opType =
                        deletedSubInfoList.size() > 0
                                ? EventType.CONNECT : EventType.ONLY_CONNECT;
                consumerEventManager
                        .addConnectEvent(consumerId,
                                new ConsumerEvent(rebalanceId, opType,
                                        addedSubInfoList, EventStatus.TODO));
                for (SubscribeInfo info : addedSubInfoList) {
                    logger.info(strBuffer.append("[Connect]")
                            .append(info.toString()).toString());
                    strBuffer.delete(0, strBuffer.length());
                }
            }
        }
    }

    /**
     * process Reset balance
     */
    public void processResetbalance(long rebalanceId, boolean isFirstReb, List<String> groups) {
        // #lizard forgives
        final StringBuilder strBuffer = new StringBuilder(512);
        Map<String, Map<String, Map<String, Partition>>> finalSubInfoMap = null;
        // choose different load balance strategy
        if (isFirstReb) {
            finalSubInfoMap =  this.loadBalancer.resetBukAssign(consumerHolder,
                    topicPSInfoManager, groups, this.zkOffsetStorage,
                    this.defaultBrokerConfManager, strBuffer);
        } else {
            finalSubInfoMap = this.loadBalancer.resetBalanceCluster(currentSubInfo,
                    consumerHolder, topicPSInfoManager, groups, this.zkOffsetStorage,
                    this.defaultBrokerConfManager, strBuffer);
        }
        // filter
        for (Map.Entry<String, Map<String, Map<String, Partition>>> entry
                : finalSubInfoMap.entrySet()) {
            String consumerId = entry.getKey();
            if (consumerId == null) {
                continue;
            }
            Tuple2<String, ConsumerInfo> tupleInfo =
                    consumerHolder.getConsumeTupleInfo(consumerId);
            if (tupleInfo == null
                    || tupleInfo.f0 == null
                    || tupleInfo.f1 == null) {
                continue;
            }
            // allocate partitions to consumers
            List<String> blackTopicList =
                    this.defaultBrokerConfManager.getBdbBlackTopicList(tupleInfo.f0);
            Map<String, Map<String, Partition>> topicSubPartMap = entry.getValue();
            List<SubscribeInfo> deletedSubInfoList = new ArrayList<>();
            List<SubscribeInfo> addedSubInfoList = new ArrayList<>();
            for (Map.Entry<String, Map<String, Partition>> topicEntry : topicSubPartMap.entrySet()) {
                String topic = topicEntry.getKey();
                Map<String, Partition> finalPartMap = topicEntry.getValue();
                Map<String, Partition> currentPartMap = null;
                Map<String, Map<String, Partition>> curTopicSubInfoMap =
                        currentSubInfo.get(consumerId);
                if (curTopicSubInfoMap == null
                        || curTopicSubInfoMap.get(topic) == null) {
                    currentPartMap = new HashMap<>();
                } else {
                    currentPartMap = curTopicSubInfoMap.get(topic);
                    if (currentPartMap == null) {
                        currentPartMap = new HashMap<>();
                    }
                }
                // filter
                for (Partition currentPart : currentPartMap.values()) {
                    if ((blackTopicList.contains(currentPart.getTopic()))
                            || (finalPartMap.get(currentPart.getPartitionKey()) == null)) {
                        deletedSubInfoList
                                .add(new SubscribeInfo(consumerId, tupleInfo.f0,
                                        tupleInfo.f1.isOverTLS(), currentPart));
                    }
                }
                for (Partition finalPart : finalPartMap.values()) {
                    if ((currentPartMap.get(finalPart.getPartitionKey()) == null)
                            && (!blackTopicList.contains(finalPart.getTopic()))) {
                        addedSubInfoList.add(new SubscribeInfo(consumerId, tupleInfo.f0,
                                tupleInfo.f1.isOverTLS(), finalPart));
                    }
                }
            }
            // generate consumer event
            if (deletedSubInfoList.size() > 0) {
                EventType opType =
                        addedSubInfoList.size() > 0
                                ? EventType.DISCONNECT : EventType.ONLY_DISCONNECT;
                consumerEventManager.addDisconnectEvent(consumerId,
                        new ConsumerEvent(rebalanceId, opType,
                                deletedSubInfoList, EventStatus.TODO));
                for (SubscribeInfo info : deletedSubInfoList) {
                    logger.info(strBuffer.append("[ResetDisconnect]")
                            .append(info.toString()).toString());
                    strBuffer.delete(0, strBuffer.length());
                }
            }
            if (addedSubInfoList.size() > 0) {
                EventType opType =
                        deletedSubInfoList.size() > 0
                                ? EventType.CONNECT : EventType.ONLY_CONNECT;
                consumerEventManager.addConnectEvent(consumerId,
                        new ConsumerEvent(rebalanceId, opType,
                                addedSubInfoList, EventStatus.TODO));
                for (SubscribeInfo info : addedSubInfoList) {
                    logger.info(strBuffer.append("[ResetConnect]")
                            .append(info.toString()).toString());
                    strBuffer.delete(0, strBuffer.length());
                }
            }
        }
    }

    /**
     * check if master subscribe info consist consumer subscribe info
     *
     * @param masterSubInfoMap
     * @param consumerSubInfoList
     * @return
     */
    private boolean checkIfConsist(Map<String, Map<String, Partition>> masterSubInfoMap,
                                   List<SubscribeInfo> consumerSubInfoList) {
        int masterInfoSize = 0;
        for (Map.Entry<String, Map<String, Partition>> entry
                : masterSubInfoMap.entrySet()) {
            masterInfoSize += entry.getValue().size();
        }
        if (masterInfoSize != consumerSubInfoList.size()) {
            return false;
        }
        for (SubscribeInfo info : consumerSubInfoList) {
            Map<String, Partition> masterInfoMap =
                    masterSubInfoMap.get(info.getTopic());
            if (masterInfoMap == null || masterInfoMap.isEmpty()) {
                return false;
            }

            if (masterInfoMap.get(info.getPartition().getPartitionKey()) == null) {
                return false;
            }
        }
        return true;
    }

    /**
     * get broker authorized info
     *
     * @param authAuthorizedToken
     * @return
     */
    private MasterBrokerAuthorizedInfo.Builder genBrokerAuthorizedInfo(String authAuthorizedToken) {
        MasterBrokerAuthorizedInfo.Builder authorizedBuilder = MasterBrokerAuthorizedInfo.newBuilder();
        authorizedBuilder.setVisitAuthorizedToken(visitTokenManager.getBrokerVisitTokens());
        if (TStringUtils.isNotBlank(authAuthorizedToken)) {
            authorizedBuilder.setAuthAuthorizedToken(authAuthorizedToken);
        }
        return authorizedBuilder;
    }

    /**
     * get client authorized info
     *
     * @param authAuthorizedToken
     * @return
     */
    private MasterAuthorizedInfo.Builder genAuthorizedInfo(String authAuthorizedToken, boolean isBroker) {
        MasterAuthorizedInfo.Builder authorizedBuilder = MasterAuthorizedInfo.newBuilder();
        if (isBroker) {
            authorizedBuilder.setVisitAuthorizedToken(visitTokenManager.getFreshVisitToken());
        } else {
            authorizedBuilder.setVisitAuthorizedToken(visitTokenManager.getCurVisitToken());
        }
        if (TStringUtils.isNotBlank(authAuthorizedToken)) {
            authorizedBuilder.setAuthAuthorizedToken(authAuthorizedToken);
        }
        return authorizedBuilder;
    }

    /**
     * get need balance group list
     *
     * @param strBuffer
     * @return
     */
    private List<String> getNeedToBalanceGroupList(final StringBuilder strBuffer) {
        List<String> groupsNeedToBalance = new ArrayList<>();
        Set<String> groupHasUnfinishedEvent = new HashSet<>();
        if (consumerEventManager.hasEvent()) {
            Set<String> consumerIdSet =
                    consumerEventManager.getUnProcessedIdSet();
            Map<String, TimeoutInfo> heartbeatMap =
                    heartbeatManager.getConsumerRegMap();
            for (String consumerId : consumerIdSet) {
                if (consumerId == null) {
                    continue;
                }
                String group = consumerHolder.getGroup(consumerId);
                if (group == null) {
                    continue;
                }
                if (heartbeatMap.get(getConsumerKey(group, consumerId)) == null) {
                    continue;
                }
                if (consumerEventManager.getUnfinishedCount(group)
                        >= MAX_BALANCE_DELAY_TIME) {
                    consumerEventManager.removeAll(consumerId);
                    logger.info(strBuffer.append("Unfinished event for group :")
                            .append(group).append(" exceed max balanceDelayTime=")
                            .append(MAX_BALANCE_DELAY_TIME).append(", clear consumer: ")
                            .append(consumerId).append(" unProcessed events.").toString());
                    strBuffer.delete(0, strBuffer.length());
                } else {
                    groupHasUnfinishedEvent.add(group);
                }
            }
        }
        consumerEventManager.updateUnfinishedCountMap(groupHasUnfinishedEvent);
        List<String> allGroups = consumerHolder.getAllGroup();
        if (groupHasUnfinishedEvent.isEmpty()) {
            for (String group : allGroups) {
                if (group != null) {
                    groupsNeedToBalance.add(group);
                }
            }
        } else {
            for (String group : allGroups) {
                if (group != null) {
                    if (!groupHasUnfinishedEvent.contains(group)) {
                        groupsNeedToBalance.add(group);
                    }
                }
            }
        }
        return groupsNeedToBalance;
    }

    /**
     * Stop chores
     */
    private void stopChores() {
        if (this.balancerChore != null) {
            this.balancerChore.interrupt();
        }
    }

    /**
     * Start balance chore
     *
     * @param master
     * @return
     */
    private Thread startBalancerChore(final TMaster master) {
        Chore chore = new Chore("BalancerChore",
                masterConfig.getConsumerBalancePeriodMs(), master) {
            @Override
            protected void chore() {
                try {
                    master.balance(master);
                } catch (Throwable e) {
                    logger.warn("Rebalance throwable error: ", e);
                }
            }
        };
        return ThreadUtils.setDaemonThreadRunning(chore.getThread());
    }

    public void stop() {
        stop("");
    }

    /**
     * stop master service
     *
     * @param why stop reason
     */
    @Override
    public void stop(String why) {
        logger.info(why);
        this.stopped = true;
        try {
            webServer.stop();
            rpcServiceFactory.destroy();
            executor.shutdown();
            stopChores();
            heartbeatManager.stop();
            zkOffsetStorage.close();
            defaultBrokerConfManager.stop();
            defaultBdbStoreService.stop();
            visitTokenManager.stop();
            if (!shutdownHooked.get()) {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            }
        } catch (Exception e) {
            logger.error("Stop master error!", e);
        }
    }

    @Override
    public boolean isStopped() {
        return this.stopped;
    }

    /**
     * Getter
     *
     * @return
     */
    public ConcurrentHashMap<String, Map<String, Map<String, Partition>>> getCurrentSubInfoMap() {
        return currentSubInfo;
    }

    public TopicPSInfoManager getTopicPSInfoManager() {
        return topicPSInfoManager;
    }

    public BrokerInfoHolder getBrokerHolder() {
        return brokerHolder;
    }

    public ProducerInfoHolder getProducerHolder() {
        return producerHolder;
    }

    public ConsumerInfoHolder getConsumerHolder() {
        return consumerHolder;
    }


    /**
     * check bdb data path, create it if not exist
     *
     * @throws Exception
     */
    private void checkAndCreateBdbDataPath() throws Exception {
        String bdbEnvPath = this.masterConfig.getMetaDataPath();
        final File dir = new File(bdbEnvPath);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new Exception(new StringBuilder(256)
                    .append("Could not make bdb data directory ")
                    .append(dir.getAbsolutePath()).toString());
        }
        if (!dir.isDirectory() || !dir.canRead()) {
            throw new Exception(new StringBuilder(256)
                    .append("bdb data path ")
                    .append(dir.getAbsolutePath())
                    .append(" is not a readable directory").toString());
        }
    }

    private void checkNodeStatus(String clientId, final StringBuilder strBuffer) throws Exception {
        if (!defaultBdbStoreService.isMaster()) {
            throw new StandbyException(strBuffer.append(masterAddInfo.getHostPortStr())
                    .append(" is not master now. the connecting client id is ")
                    .append(clientId).toString());
        }

    }

    private abstract static class AbstractReleaseRunner {
        abstract void run(String arg);
    }

    private class ReleaseConsumer extends AbstractReleaseRunner {
        @Override
        void run(String arg) {
            String[] nodeStrs = arg.split(TokenConstants.GROUP_SEP);
            String consumerId = nodeStrs[0];
            String group = nodeStrs[1];
            Integer lid = null;
            try {
                lid = masterRowLock.getLock(null, StringUtils.getBytesUtf8(consumerId), true);
                ConsumerInfo info = consumerHolder.removeConsumer(group, consumerId);
                currentSubInfo.remove(consumerId);
                consumerEventManager.removeAll(consumerId);
                List<ConsumerInfo> consumerList =
                        consumerHolder.getConsumerList(group);
                if (consumerList == null || consumerList.isEmpty()) {
                    if (info != null) {
                        for (String topic : info.getTopicSet()) {
                            topicPSInfoManager.removeTopicSubInfo(topic, group);
                        }
                    }
                }
            } catch (IOException e) {
                logger.warn("Failed to lock.", e);
            } finally {
                if (lid != null) {
                    masterRowLock.releaseRowLock(lid);
                }
            }
        }
    }

    private class ReleaseBroker extends AbstractReleaseRunner {
        @Override
        void run(String arg) {
            int brokerId = Integer.parseInt(arg);
            BrokerInfo broker = brokerHolder.removeBroker(brokerId);
            if (broker != null) {
                List<TopicInfo> topicInfoList =
                        topicPSInfoManager.getBrokerPubInfoList(broker);
                if (topicInfoList != null) {
                    updateTopicsInternal(broker, topicInfoList, EventType.DISCONNECT);
                }
                defaultBrokerConfManager.resetBrokerReportInfo(broker.getBrokerId());
            }
        }
    }

    private class ReleaseProducer extends AbstractReleaseRunner {
        @Override
        void run(String clientId) {
            if (clientId != null) {
                ProducerInfo info = producerHolder.removeProducer(clientId);
                if (info != null) {
                    topicPSInfoManager.rmvProducerTopicPubInfo(clientId, info.getTopicSet());
                }
            }
        }
    }

    private final class ShutdownHook extends Thread {
        @Override
        public void run() {
            if (shutdownHooked.compareAndSet(false, true)) {
                TMaster.this.stop("TMaster shutdown hooked.");
            }
        }
    }

}
