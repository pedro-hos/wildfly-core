/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.jboss.as.host.controller.util;

import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.READ_RESOURCE_OPERATION;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.RECURSIVE;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.jboss.as.controller.CapabilityRegistry;
import org.jboss.as.controller.ControlledProcessState;
import org.jboss.as.controller.DelegatingResourceDefinition;
import org.jboss.as.controller.ExpressionResolver;
import org.jboss.as.controller.ManagementModel;
import org.jboss.as.controller.ModelController;
import org.jboss.as.controller.OperationFailedException;
import org.jboss.as.controller.PathAddress;
import org.jboss.as.controller.PathElement;
import org.jboss.as.controller.ProcessType;
import org.jboss.as.controller.ProxyController;
import org.jboss.as.controller.ResourceBuilder;
import org.jboss.as.controller.ResourceDefinition;
import org.jboss.as.controller.RunningMode;
import org.jboss.as.controller.audit.ManagedAuditLogger;
import org.jboss.as.controller.capability.registry.ImmutableCapabilityRegistry;
import org.jboss.as.controller.descriptions.NonResolvingResourceDescriptionResolver;
import org.jboss.as.controller.extension.ExtensionRegistry;
import org.jboss.as.controller.operations.common.Util;
import org.jboss.as.controller.persistence.ConfigurationPersistenceException;
import org.jboss.as.controller.persistence.ExtensibleConfigurationPersister;
import org.jboss.as.controller.registry.ManagementResourceRegistration;
import org.jboss.as.controller.registry.Resource;
import org.jboss.as.controller.services.path.PathManagerService;
import org.jboss.as.controller.transform.OperationTransformer;
import org.jboss.as.controller.transform.ResourceTransformationContext;
import org.jboss.as.controller.transform.TransformationContext;
import org.jboss.as.controller.transform.TransformationTarget;
import org.jboss.as.controller.transform.Transformers;
import org.jboss.as.domain.controller.DomainController;
import org.jboss.as.domain.controller.LocalHostControllerInfo;
import org.jboss.as.domain.controller.SlaveRegistrationException;
import org.jboss.as.host.controller.HostControllerEnvironment;
import org.jboss.as.host.controller.ignored.IgnoredDomainResourceRegistry;
import org.jboss.as.host.controller.operations.LocalHostControllerInfoImpl;
import org.jboss.as.protocol.mgmt.ManagementChannelHandler;
import org.jboss.as.repository.ContentReference;
import org.jboss.as.repository.ContentRepository;
import org.jboss.as.repository.HostFileRepository;
import org.jboss.as.version.ProductConfig;
import org.jboss.dmr.ModelNode;
import org.jboss.msc.service.ServiceBuilder;
import org.jboss.msc.service.ServiceName;
import org.jboss.msc.service.ServiceTarget;
import org.jboss.vfs.VirtualFile;
import org.junit.After;
import org.junit.Before;
import org.wildfly.core.AbstractControllerTestBase;

/**
 * @author Emanuel Muckenhuber
 */
public abstract class HostControllerTestBase extends AbstractControllerTestBase {


    private final boolean useDelegateRootResourceDefinition;
    private TestModelControllerService controllerService;
    protected final String hostName;
    protected final LocalHostControllerInfoImpl hostControllerInfo;
    protected final HostControllerEnvironment hostControllerEnvironment;
    protected final DomainController domainController;
    protected volatile DelegatingResourceDefinitionInitializer initializer;
    private final TestDelegatingResourceDefiniton rootResourceDefinition;
    protected final CapabilityRegistry capabilityRegistry;

    protected HostControllerTestBase() {
        this(ProcessType.EMBEDDED_SERVER);
    }

    protected HostControllerTestBase(ProcessType processType) {
        this("secondary", processType, false);
    }

    protected HostControllerTestBase(String hostName, ProcessType processType, boolean useDelegateRootResourceDefinition) {
        super(processType);
        this.hostName = hostName;
        this.useDelegateRootResourceDefinition = useDelegateRootResourceDefinition;
        hostControllerEnvironment = createHostControllerEnvironment(hostName);
        hostControllerInfo = new LocalHostControllerInfoImpl(new ControlledProcessState(false), hostControllerEnvironment);
        domainController = new MockDomainController();
        rootResourceDefinition = useDelegateRootResourceDefinition ?  new TestDelegatingResourceDefiniton() : null;
        capabilityRegistry = new CapabilityRegistry(processType.isServer());
    }

    public TestModelControllerService getControllerService() {
        return controllerService;
    }

    @Before
    public void setupController() throws InterruptedException {
        ServiceTarget target = getServiceTarget();
        if (useDelegateRootResourceDefinition) {
            initializer = createInitializer();
            controllerService = new ModelControllerService(getAuditLogger(), rootResourceDefinition);
        } else {
            controllerService = new ModelControllerService(getAuditLogger());
        }
        ServiceBuilder<ModelController> builder = target.addService(ServiceName.of("ModelController"), controllerService);
        builder.install();
        controllerService.awaitStartup(30, TimeUnit.SECONDS);
        controller = controllerService.getValue();
    }

    @After
    public void shutdownServiceContainer() {
        shutdownContainer();
    }

    protected DelegatingResourceDefinitionInitializer createInitializer() {
        throw new IllegalStateException(this.getClass().getName() + " created with useDelegateRootResourceDefinition=false, needs to override createInitializer()");
    }

    protected TestDelegatingResourceDefiniton getDelegatingResourceDefiniton() {
        if (!useDelegateRootResourceDefinition) {
            throw new IllegalStateException("Test is not set up to use a delegating resource definition");
        }
        return rootResourceDefinition;
    }

    protected ModelNode readResourceRecursive() throws Exception {
        ModelNode op = Util.createOperation(READ_RESOURCE_OPERATION, PathAddress.EMPTY_ADDRESS);
        op.get(RECURSIVE).set(true);
        return executeForResult(op);
    }

    public static class NoopTransformers implements Transformers {

        @Override
        public TransformationTarget getTarget() {
            return null;
        }

        @Override
        public OperationTransformer.TransformedOperation transformOperation(TransformationContext context, ModelNode operation)
                throws OperationFailedException {
            return new OperationTransformer.TransformedOperation(operation, OperationTransformer.TransformedOperation.ORIGINAL_RESULT);
        }

        @Override
        public OperationTransformer.TransformedOperation transformOperation(TransformationInputs transformationInputs, ModelNode operation)
                throws OperationFailedException {
            return new OperationTransformer.TransformedOperation(operation, OperationTransformer.TransformedOperation.ORIGINAL_RESULT);
        }

        @Override
        public Resource transformResource(ResourceTransformationContext context, Resource resource)
                throws OperationFailedException {
            return resource;
        }

        @Override
        public Resource transformRootResource(TransformationInputs transformationInputs, Resource resource)
                throws OperationFailedException {
            return resource;
        }

        @Override
        public Resource transformRootResource(TransformationInputs transformationInputs, Resource resource, ResourceIgnoredTransformationRegistry ignoredTransformationRegistry) throws OperationFailedException {
            return resource;
        }
    }

    protected class ModelControllerService extends TestModelControllerService {

        public ModelControllerService(final ManagedAuditLogger auditLogger) {
            super(HostControllerTestBase.this.processType, new EmptyConfigurationPersister(), new ControlledProcessState(true),
                    ResourceBuilder.Factory.create(PathElement.pathElement("root"), NonResolvingResourceDescriptionResolver.INSTANCE).build(),
                    auditLogger, initializer, capabilityRegistry);
        }

        public ModelControllerService(final ManagedAuditLogger auditLogger,
                               DelegatingResourceDefinition rootResourceDefinition) {
            super(HostControllerTestBase.this.processType, new EmptyConfigurationPersister(), new ControlledProcessState(true),
                    rootResourceDefinition, auditLogger, initializer, capabilityRegistry);
        }

        @Override
        protected boolean boot(List<ModelNode> bootOperations, boolean rollbackOnRuntimeFailure)
                throws ConfigurationPersistenceException {
            try {
                addBootOperations(bootOperations);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return super.boot(bootOperations, rollbackOnRuntimeFailure);
        }

        protected void initModel(ManagementModel managementModel, Resource modelControllerResource) {
            try {
                HostControllerTestBase.this.initModel(managementModel);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }


    private static HostControllerEnvironment createHostControllerEnvironment(String hostName) {
        //Copied from core-model-test
        try {
            Map<String, String> props = new HashMap<String, String>();
            File home = new File("target/wildfly");
            delete(home);
            home.mkdir();
            props.put(HostControllerEnvironment.HOME_DIR, home.getAbsolutePath());

            File domain = new File(home, "domain");
            domain.mkdir();
            props.put(HostControllerEnvironment.DOMAIN_BASE_DIR, domain.getAbsolutePath());

            File configuration = new File(domain, "configuration");
            configuration.mkdir();
            props.put(HostControllerEnvironment.DOMAIN_CONFIG_DIR, configuration.getAbsolutePath());

            props.put(HostControllerEnvironment.HOST_NAME, hostName);

            boolean isRestart = false;
            String modulePath = "";
            InetAddress processControllerAddress = InetAddress.getLocalHost();
            Integer processControllerPort = 9999;
            InetAddress hostControllerAddress = InetAddress.getLocalHost();
            Integer hostControllerPort = 1234;
            String defaultJVM = null;
            String domainConfig = null;
            String initialDomainConfig = null;
            String hostConfig = null;
            String initialHostConfig = null;
            RunningMode initialRunningMode = RunningMode.NORMAL;
            boolean backupDomainFiles = false;
            boolean useCachedDc = false;
            ProductConfig productConfig = ProductConfig.fromFilesystemSlot(null, "", props);
            return new HostControllerEnvironment(props, isRestart, modulePath, processControllerAddress, processControllerPort,
                    hostControllerAddress, hostControllerPort, defaultJVM, domainConfig, initialDomainConfig, hostConfig, initialHostConfig,
                    initialRunningMode, backupDomainFiles, useCachedDc, productConfig);
        } catch (UnknownHostException e) {
            // AutoGenerated
            throw new RuntimeException(e);
        }
    }

    public class MockDomainController implements DomainController {

        @Override
        public RunningMode getCurrentRunningMode() {
            return null;
        }

        @Override
        public LocalHostControllerInfo getLocalHostInfo() {
            return hostControllerInfo;
        }

        @Override
        public void registerRemoteHost(String hostName, ManagementChannelHandler handler, Transformers transformers,
                                       Long remoteConnectionId, boolean registerProxyController) throws SlaveRegistrationException {
        }

        @Override
        public boolean isHostRegistered(String id) {
            return false;
        }

        @Override
        public void unregisterRemoteHost(String id, Long remoteConnectionId, boolean cleanShutdown) {
        }

        @Override
        public void pingRemoteHost(String hostName) {
        }

        @Override
        public void registerRunningServer(ProxyController serverControllerClient) {
        }

        @Override
        public void unregisterRunningServer(String serverName) {
        }

        @Override
        public ModelNode getProfileOperations(String profileName) {
            return new ModelNode().setEmptyList();
        }

        @Override
        public HostFileRepository getLocalFileRepository() {
            return null;
        }

        @Override
        public HostFileRepository getRemoteFileRepository() {
            return null;
        }

        @Override
        public void stopLocalHost() {
        }

        @Override
        public void stopLocalHost(int exitCode) {
        }

        @Override
        public ExtensionRegistry getExtensionRegistry() {
            return null;
        }

        @Override
        public ImmutableCapabilityRegistry getCapabilityRegistry() {
            return capabilityRegistry;
        }

        @Override
        public ExpressionResolver getExpressionResolver() {
            return ExpressionResolver.TEST_RESOLVER;
        }

        @Override
        public void initializeMasterDomainRegistry(ManagementResourceRegistration root, ExtensibleConfigurationPersister configurationPersister, ContentRepository contentRepository, HostFileRepository fileRepository, ExtensionRegistry extensionRegistry, PathManagerService pathManager) {
        }

        @Override
        public void initializeSlaveDomainRegistry(ManagementResourceRegistration root, ExtensibleConfigurationPersister configurationPersister, ContentRepository contentRepository, HostFileRepository fileRepository, LocalHostControllerInfo hostControllerInfo, ExtensionRegistry extensionRegistry, IgnoredDomainResourceRegistry ignoredDomainResourceRegistry, PathManagerService pathManager) {
        }
    }

    private static void delete(File file) {
        if (file.isDirectory()) {
            for (File child : file.listFiles()) {
                delete(child);
            }
        }
        file.delete();
    }

    public interface DelegatingResourceDefinitionInitializer {
        void setDelegate();
    }


    protected class TestDelegatingResourceDefiniton extends DelegatingResourceDefinition {
        @Override
        public void setDelegate(ResourceDefinition delegate) {
            super.setDelegate(delegate);
        }
    }

    public static class TestRepository implements ContentRepository, HostFileRepository {
        @Override
        public byte[] addContent(InputStream stream) throws IOException {
            return new byte[0];
        }

        @Override
        public void addContentReference(ContentReference reference) {

        }

        @Override
        public VirtualFile getContent(byte[] hash) {
            return null;
        }

        @Override
        public boolean hasContent(byte[] hash) {
            return false;
        }

        @Override
        public boolean syncContent(ContentReference reference) {
            return true;
        }

        @Override
        public void removeContent(ContentReference reference) {

        }

        @Override
        public Map<String, Set<String>> cleanObsoleteContent() {
            return null;
        }

        @Override
        public File getFile(String relativePath) {
            return null;
        }

        @Override
        public File getConfigurationFile(String relativePath) {
            return null;
        }

        @Override
        public File[] getDeploymentFiles(ContentReference reference) {
            return new File[0];
        }

        @Override
        public File getDeploymentRoot(ContentReference reference) {
            return null;
        }

        @Override
        public void deleteDeployment(ContentReference reference) {

        }
    }
}
