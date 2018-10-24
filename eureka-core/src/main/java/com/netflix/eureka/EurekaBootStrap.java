/*
 * Copyright 2012 Netflix, Inc.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package com.netflix.eureka;

import javax.servlet.ServletContext;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import java.util.Date;

import com.netflix.appinfo.ApplicationInfoManager;
import com.netflix.appinfo.CloudInstanceConfig;
import com.netflix.appinfo.DataCenterInfo;
import com.netflix.appinfo.EurekaInstanceConfig;
import com.netflix.appinfo.InstanceInfo;
import com.netflix.appinfo.MyDataCenterInstanceConfig;
import com.netflix.appinfo.providers.EurekaConfigBasedInstanceInfoProvider;
import com.netflix.config.ConfigurationManager;
import com.netflix.config.DeploymentContext;
import com.netflix.discovery.DefaultEurekaClientConfig;
import com.netflix.discovery.DiscoveryClient;
import com.netflix.discovery.EurekaClient;
import com.netflix.discovery.EurekaClientConfig;
import com.netflix.discovery.converters.JsonXStream;
import com.netflix.discovery.converters.XmlXStream;
import com.netflix.eureka.aws.AwsBinder;
import com.netflix.eureka.aws.AwsBinderDelegate;
import com.netflix.eureka.cluster.PeerEurekaNodes;
import com.netflix.eureka.registry.AwsInstanceRegistry;
import com.netflix.eureka.registry.PeerAwareInstanceRegistry;
import com.netflix.eureka.registry.PeerAwareInstanceRegistryImpl;
import com.netflix.eureka.resources.DefaultServerCodecs;
import com.netflix.eureka.resources.ServerCodecs;
import com.netflix.eureka.util.EurekaMonitors;
import com.thoughtworks.xstream.XStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The class that kick starts the eureka server.
 *
 * <p>
 * The eureka server is configured by using the configuration
 * {@link EurekaServerConfig} specified by <em>eureka.server.props</em> in the
 * classpath.  The eureka client component is also initialized by using the
 * configuration {@link EurekaInstanceConfig} specified by
 * <em>eureka.client.props</em>. If the server runs in the AWS cloud, the eureka
 * server binds it to the elastic ip as specified.
 * </p>
 *
 * @author Karthik Ranganathan, Greg Kim, David Liu
 *
 */
public class EurekaBootStrap implements ServletContextListener {
    private static final Logger logger = LoggerFactory.getLogger(EurekaBootStrap.class);

    private static final String TEST = "test";

    private static final String ARCHAIUS_DEPLOYMENT_ENVIRONMENT = "archaius.deployment.environment";

    private static final String EUREKA_ENVIRONMENT = "eureka.environment";

    private static final String CLOUD = "cloud";
    private static final String DEFAULT = "default";

    private static final String ARCHAIUS_DEPLOYMENT_DATACENTER = "archaius.deployment.datacenter";

    private static final String EUREKA_DATACENTER = "eureka.datacenter";

    protected volatile EurekaServerContext serverContext;
    protected volatile AwsBinder awsBinder;
    
    private EurekaClient eurekaClient;

    /**
     * Construct a default instance of Eureka boostrap
     */
    public EurekaBootStrap() {
        this(null);
    }
    
    /**
     * Construct an instance of eureka bootstrap with the supplied eureka client
     * 
     * @param eurekaClient the eureka client to bootstrap
     */
    public EurekaBootStrap(EurekaClient eurekaClient) {
        this.eurekaClient = eurekaClient;
    }

    /**
     * Initializes Eureka, including syncing up with other Eureka peers and publishing the registry.
     *
     * @see
     * javax.servlet.ServletContextListener#contextInitialized(javax.servlet.ServletContextEvent)
     */
    @Override
    public void contextInitialized(ServletContextEvent event) {
        try {
            //初始化 一个ConfigurationManager 配置管理器之类的东东  用的 double check volatile 单例安全模式
            //创建了一个 ConcurrentCompositeConfiguration 实例 然后触发了 clear()方法 fireEvent()发布了一个事件（EVENT_CLEAR）
            //而且是父类的方法，这个应该是 config组件的
            //然后往 ConcurrentCompositeConfiguration 这个里面加入了一系列的config
            initEurekaEnvironment();
            initEurekaServerContext();

            ServletContext sc = event.getServletContext();
            sc.setAttribute(EurekaServerContext.class.getName(), serverContext);
        } catch (Throwable e) {
            logger.error("Cannot bootstrap eureka server :", e);
            throw new RuntimeException("Cannot bootstrap eureka server :", e);
        }
    }

    /**
     * Users can override to initialize the environment themselves.
     */
    protected void initEurekaEnvironment() throws Exception {
        logger.info("Setting the eureka configuration..");
        // 初始化 数据中心 默认为DEFAULT  eureka.datacenter
        String dataCenter = ConfigurationManager.getConfigInstance().getString(EUREKA_DATACENTER);
        if (dataCenter == null) {
            logger.info("Eureka data center value eureka.datacenter is not set, defaulting to default");
            ConfigurationManager.getConfigInstance().setProperty(ARCHAIUS_DEPLOYMENT_DATACENTER, DEFAULT);
        } else {
            ConfigurationManager.getConfigInstance().setProperty(ARCHAIUS_DEPLOYMENT_DATACENTER, dataCenter);
        }
        //初始化 运行eureka环境 默认为 TEST
        String environment = ConfigurationManager.getConfigInstance().getString(EUREKA_ENVIRONMENT);
        if (environment == null) {
            ConfigurationManager.getConfigInstance().setProperty(ARCHAIUS_DEPLOYMENT_ENVIRONMENT, TEST);
            logger.info("Eureka environment value eureka.environment is not set, defaulting to test");
        }
    }

    /**
     * init hook for server context. Override for custom logic.
     */
    protected void initEurekaServerContext() throws Exception {

        //弄了一个接口  就是从这个EurekaServerConfig 这个接口 可以获取 eureka-server.properties 所有的 key-value配置项 比较灵活
        // 创建 DefaultEurekaServerConfig 这个接口的时候 有一个init()方法，加载eurekaPropsFile 这个文件名的配置文件 也就是会加载
        // eureka-server.properties 配置    String eurekaPropsFile = EUREKA_PROPS_FILE.get(); 返回的就是 默认default  eureka-server
        // 然后把 eureka-server.properties 读到Properties 里去 把原来的配置 覆盖掉 把加载出来的配置 都放到ConfdigurationManager中去管理
        //如果 要调用每个配置项  然后在DefaultEurekaServerConfig的各种获取配置项的方法中，配置项的名字是在各个方法中硬编码的，
        // 是从一个DynamicPropertyFactory里面去获取的  在从DynamicPropertyFactory中获取配置项的时候，如果你没配置，那么就用默认值 可以在DefaultEurekaServerConfig
        //里看到所有的配置项
        EurekaServerConfig eurekaServerConfig = new DefaultEurekaServerConfig();

        // For backward compatibility
        JsonXStream.getInstance().registerConverter(new V1AwareInstanceInfoConverter(), XStream.PRIORITY_VERY_HIGH);
        XmlXStream.getInstance().registerConverter(new V1AwareInstanceInfoConverter(), XStream.PRIORITY_VERY_HIGH);

        logger.info("Initializing the eureka client...");
        logger.info(eurekaServerConfig.getJsonCodecName());
        ServerCodecs serverCodecs = new DefaultServerCodecs(eurekaServerConfig);


        ApplicationInfoManager applicationInfoManager = null;
        //EurekaInstanceConfig 和 EurekaServerConfig 这个类似  应该是加载所有的 eureka-client.properties 文件都加载到 ConfigurationManager中去
        //然后基于 EurekaInstanceConfig 接口 获取所有的配置项
        // EurekaInstanceConfig 是服务实例的配置项  这个是eureka-client的配置  做为eureka-server服务端 也需要 做eureka-client向别人注册
        if (eurekaClient == null) {
            EurekaInstanceConfig instanceConfig = isCloud(ConfigurationManager.getDeploymentContext())
                    ? new CloudInstanceConfig()
                    : new MyDataCenterInstanceConfig();

            //用 EurekaInstanceConfig 创建出一个 InstanceInfo  用构造器模式 用了一个静态内部类  InstanceInfo.Builder构造一个复杂的服务实例 InstanceInfo对象
            //  InstanceInfo 可以看做这个服务实例对象  eureka-server本身也是一个实例 也需要注册到 eureka server上去

            //基于EurekaInstanceConfig和InstnaceInfo，构造了一个ApplicationInfoManager，来管理实例信息 和配置信息
            applicationInfoManager = new ApplicationInfoManager(
                    instanceConfig, new EurekaConfigBasedInstanceInfoProvider(instanceConfig).get());

            //这个也是EurekaClientConfig的配置信息和EurekaInstanceConfig差不多，但是EurekaClientConfig 是eureka-client的一些配置
            // 也是去读eureka-client.propreties  包含了 EurekaTransportConfig 配置 通讯配置信息等
            EurekaClientConfig eurekaClientConfig = new DefaultEurekaClientConfig();
            //用服务实例信息  和 eureka client 相关配置 构建了一个 eurekaClient 是用子类 DiscoveryClient 构建的
            // 1 读取 EurekaClientConfig 包含（EurekaTransportConfig通讯的配置）
            // 2 读取  EurekaInstanceConfig 和  InstanceInfo 也就是 ApplicationInfoManager
            // 3 判断是否要注册 和 抓取注册表  如果 false  就释放一下 资源
            // 4  如果 需要注册和抓取注册表  初始化了 调度线程池（大小为 2） 心跳线程池（5） 刷新缓存线程池（5）
            // 5 如果 需要注册和抓取注册表 初始化 eureka client 和 eureka server 之间的通讯组件 对应各自的类似的 httpclient的封装
            // 6.初始化 定时任务 里面有 心跳  刷新缓存等服务    服务副本实例任务创建

            /**
             * 这里隐藏了 eureka client 注册
             */
            eurekaClient = new DiscoveryClient(applicationInfoManager, eurekaClientConfig);
        } else {
            applicationInfoManager = eurekaClient.getApplicationInfoManager();
        }
        //应该是eureka 服务的注册实例表   就是别的eureka client 注册过来的  如果是eureka server集群的话 应该也有其他eureka server的服务实力信息
        PeerAwareInstanceRegistry registry;
        if (isAws(applicationInfoManager.getInfo())) {
            registry = new AwsInstanceRegistry(
                    eurekaServerConfig,
                    eurekaClient.getEurekaClientConfig(),
                    serverCodecs,
                    eurekaClient
            );
            awsBinder = new AwsBinderDelegate(eurekaServerConfig, eurekaClient.getEurekaClientConfig(), registry, applicationInfoManager);
            awsBinder.start();
        } else {
            //这个里有个定时任务 30s执行一次 查看最近有变动服务实例 recentlyChangedQueue 如果队列的数据修改时间和当前时间相差 180s
            //也就是说 超过180s 移除它  queue只保存修改在180s的服务实例
            registry = new PeerAwareInstanceRegistryImpl(
                    eurekaServerConfig,
                    eurekaClient.getEurekaClientConfig(),
                    serverCodecs,
                    eurekaClient
            );
        }

        //应该是 PeerEurekaNodes 代表eureka server 服务集群的
        PeerEurekaNodes peerEurekaNodes = getPeerEurekaNodes(
                registry,
                eurekaServerConfig,
                eurekaClient.getEurekaClientConfig(),
                serverCodecs,
                applicationInfoManager
        );
        //就是把所有的配置等信息都初始化了  构造出了一个服务上下文 ，以后可以从这个上下文取到所有东西
        serverContext = new DefaultEurekaServerContext(
                eurekaServerConfig,
                serverCodecs,
                registry,
                peerEurekaNodes,
                applicationInfoManager
        );

        EurekaServerContextHolder.initialize(serverContext);
        //初始化eureka 集群的相关操作
        serverContext.initialize();
        logger.info("Initialized server context");

        // Copy registry from neighboring eureka node
        //从向邻的 eureka server 节点 copy 注册表信息过来 如果失败 就去找下一个
        int registryCount = registry.syncUp();
        // eureka server 有一个 TimerTask 60s执行 判断是否有client 故障  也有自我保护机制判断
        registry.openForTraffic(applicationInfoManager, registryCount);

        // Register all monitoring statistics.
        //监控信息
        EurekaMonitors.registerAllStats();
    }
    
    protected PeerEurekaNodes getPeerEurekaNodes(PeerAwareInstanceRegistry registry, EurekaServerConfig eurekaServerConfig, EurekaClientConfig eurekaClientConfig, ServerCodecs serverCodecs, ApplicationInfoManager applicationInfoManager) {
        PeerEurekaNodes peerEurekaNodes = new PeerEurekaNodes(
                registry,
                eurekaServerConfig,
                eurekaClientConfig,
                serverCodecs,
                applicationInfoManager
        );
        
        return peerEurekaNodes;
    }

    /**
     * Handles Eureka cleanup, including shutting down all monitors and yielding all EIPs.
     *
     * @see javax.servlet.ServletContextListener#contextDestroyed(javax.servlet.ServletContextEvent)
     */
    @Override
    public void contextDestroyed(ServletContextEvent event) {
        try {
            logger.info("{} Shutting down Eureka Server..", new Date().toString());
            ServletContext sc = event.getServletContext();
            sc.removeAttribute(EurekaServerContext.class.getName());

            destroyEurekaServerContext();
            destroyEurekaEnvironment();

        } catch (Throwable e) {
            logger.error("Error shutting down eureka", e);
        }
        logger.info("{} Eureka Service is now shutdown...", new Date().toString());
    }

    /**
     * Server context shutdown hook. Override for custom logic
     */
    protected void destroyEurekaServerContext() throws Exception {
        EurekaMonitors.shutdown();
        if (awsBinder != null) {
            awsBinder.shutdown();
        }
        if (serverContext != null) {
            serverContext.shutdown();
        }
    }

    /**
     * Users can override to clean up the environment themselves.
     */
    protected void destroyEurekaEnvironment() throws Exception {

    }

    protected boolean isAws(InstanceInfo selfInstanceInfo) {
        boolean result = DataCenterInfo.Name.Amazon == selfInstanceInfo.getDataCenterInfo().getName();
        logger.info("isAws returned {}", result);
        return result;
    }

    protected boolean isCloud(DeploymentContext deploymentContext) {
        logger.info("Deployment datacenter is {}", deploymentContext.getDeploymentDatacenter());
        return CLOUD.equals(deploymentContext.getDeploymentDatacenter());
    }
}
