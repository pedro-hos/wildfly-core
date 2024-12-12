/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.wildfly.core.instmgr;

import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.FAILED;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.FAILURE_DESCRIPTION;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OP;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OP_ADDR;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OUTCOME;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.RESULT;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.jboss.as.controller.CapabilityRegistry;
import org.jboss.as.controller.ControlledProcessState;
import org.jboss.as.controller.ManagementModel;
import org.jboss.as.controller.ModelControllerServiceInitialization;
import org.jboss.as.controller.OperationFailedException;
import org.jboss.as.controller.PathElement;
import org.jboss.as.controller.ProcessType;
import org.jboss.as.controller.ResourceBuilder;
import org.jboss.as.controller.ResourceDefinition;
import org.jboss.as.controller.RunningMode;
import org.jboss.as.controller.RunningModeControl;
import org.jboss.as.controller.client.Operation;
import org.jboss.as.controller.client.OperationResponse;
import org.jboss.as.controller.descriptions.NonResolvingResourceDescriptionResolver;
import org.jboss.as.controller.operations.common.Util;
import org.jboss.as.controller.persistence.ConfigurationPersistenceException;
import org.jboss.as.controller.registry.Resource;
import org.jboss.dmr.ModelNode;
import org.jboss.msc.Service;
import org.jboss.msc.service.ServiceBuilder;
import org.jboss.msc.service.ServiceController;
import org.jboss.msc.service.ServiceName;
import org.jboss.msc.service.ServiceTarget;
import org.jboss.msc.service.StabilityMonitor;
import org.wildfly.core.AbstractControllerTestBase;

/**
 * @author Emanuel Muckenhuber
 */
public abstract class InstalationManagerControllerTestBase extends AbstractControllerTestBase {
    protected final Map<ServiceName, Supplier<Object>> recordedServices = new ConcurrentHashMap<>();
    protected final ResourceDefinition resourceDefinition;
    protected CapabilityRegistry capabilityRegistry;

    protected InstalationManagerControllerTestBase(ProcessType processType, ResourceDefinition resourceDefinition) {
        super(processType);
        this.resourceDefinition = resourceDefinition;
    }

    protected InstalationManagerControllerTestBase() {
        this(ProcessType.STANDALONE_SERVER, ResourceBuilder.Factory.create(PathElement.pathElement("root"), NonResolvingResourceDescriptionResolver.INSTANCE).build());
    }

    protected ModelNode createOperation(String operationName) {
        ModelNode operation = new ModelNode();
        operation.get(OP).set(operationName);
        operation.get(OP_ADDR).setEmptyList();
        return operation;
    }

    public ModelNode executeForResult(ModelNode operation) throws OperationFailedException {
        return super.executeForResult(operation).get(RESULT);
    }

    public ModelNode executeForResult(Operation operation) throws OperationFailedException, IOException {
        return executeCheckNoFailure(operation).get(RESULT);
    }

    public ModelNode executeCheckNoFailure(Operation operation) throws OperationFailedException, IOException {
        try {
            OperationResponse response = getController().execute(operation, null, null);
            ModelNode rsp = response.getResponseNode();
            if (FAILED.equals(rsp.get(OUTCOME).asString())) {
                ModelNode fd = rsp.get(FAILURE_DESCRIPTION);
                throw new OperationFailedException(fd.toString(), fd);
            }
            return rsp;
        } finally {
            operation.close();
        }
    }

    public void setupController() throws InterruptedException {
        ServiceTarget target = getServiceTarget();
        ModelControllerService svc = createModelControllerService(processType, resourceDefinition);
        target.addService(ServiceName.of("ModelController")).setInstance(svc).install();
        svc.awaitStartup(30, TimeUnit.SECONDS);
        controller = svc.getValue();
        capabilityRegistry = svc.getCapabilityRegistry();
        ModelNode setup = Util.getEmptyOperation("setup", new ModelNode());
        controller.execute(setup, null, null, null);
    }

    public void shutdownServiceContainer() {
        shutdownContainer();
    }

    protected ModelControllerService createModelControllerService(ProcessType processType, ResourceDefinition resourceDefinition) {
        return new ModelControllerService(processType, resourceDefinition);
    }


    public class ModelControllerService extends TestModelControllerService {

        public ModelControllerService(final ProcessType processType) {
            this(processType, new RunningModeControl(RunningMode.NORMAL));
        }

        public ModelControllerService(final ProcessType processType, RunningModeControl runningModeControl) {
            this(processType, runningModeControl, null);
        }

        public ModelControllerService(final ProcessType processType, RunningModeControl runningModeControl, Supplier<ExecutorService> executorService) {
            super(processType, runningModeControl, executorService, new EmptyConfigurationPersister(), new ControlledProcessState(true),
                    ResourceBuilder.Factory.create(PathElement.pathElement("root"), NonResolvingResourceDescriptionResolver.INSTANCE).build()
            );
        }

        public ModelControllerService(final ProcessType processType, ResourceDefinition resourceDefinition) {
            super(processType, new EmptyConfigurationPersister(), new ControlledProcessState(true), resourceDefinition);
        }

        @Override
        protected boolean boot(List<ModelNode> bootOperations, boolean rollbackOnRuntimeFailure)
                throws ConfigurationPersistenceException {
            addBootOperations(bootOperations);
            return super.boot(bootOperations, rollbackOnRuntimeFailure);
        }

        protected void initModel(ManagementModel managementModel, Resource modelControllerResource) {
            try {
                InstalationManagerControllerTestBase.this.initModel(managementModel);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        @Override
        protected ModelControllerServiceInitializationParams getModelControllerServiceInitializationParams() {
            final ServiceLoader<ModelControllerServiceInitialization> sl = ServiceLoader.load(ModelControllerServiceInitialization.class);
            return new ModelControllerServiceInitializationParams(sl) {
                @Override
                public String getHostName() {
                    return null;
                }
            };
        }
    }

    public void recordService(StabilityMonitor monitor, ServiceName name) {
        ServiceBuilder<?> serviceBuilder = getContainer().subTarget().addService(name.append("test-recorder"));
        recordedServices.put(name, serviceBuilder.requires(name));
        monitor.addController(serviceBuilder.setInstance(Service.NULL).setInitialMode(ServiceController.Mode.ACTIVE).install());
    }
}
