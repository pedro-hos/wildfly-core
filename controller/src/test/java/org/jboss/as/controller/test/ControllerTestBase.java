/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.jboss.as.controller.test;

import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.FAILED;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.FAILURE_DESCRIPTION;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OP;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OP_ADDR;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OUTCOME;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.ROLLED_BACK;
import static org.junit.Assert.assertFalse;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.jboss.as.controller.CapabilityRegistry;
import org.jboss.as.controller.ControlledProcessState;
import org.jboss.as.controller.ManagementModel;
import org.jboss.as.controller.OperationFailedException;
import org.jboss.as.controller.PathAddress;
import org.jboss.as.controller.PathElement;
import org.jboss.as.controller.ProcessType;
import org.jboss.as.controller.ResourceBuilder;
import org.jboss.as.controller.ResourceDefinition;
import org.jboss.as.controller.RunningMode;
import org.jboss.as.controller.RunningModeControl;
import org.jboss.as.controller.TestModelControllerService;
import org.jboss.as.controller.descriptions.NonResolvingResourceDescriptionResolver;
import org.jboss.as.controller.notification.NotificationHandlerRegistry;
import org.jboss.as.controller.operations.common.Util;
import org.jboss.as.controller.persistence.ConfigurationPersistenceException;
import org.jboss.as.controller.registry.Resource;
import org.jboss.dmr.ModelNode;
import org.jboss.msc.service.ServiceName;
import org.jboss.msc.service.ServiceTarget;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.wildfly.test.controller.base.AbstractControllerTestBase;

/**
 * @author Emanuel Muckenhuber
 */
public abstract class ControllerTestBase extends AbstractControllerTestBase {

    protected CapabilityRegistry capabilityRegistry;
    private NotificationHandlerRegistry notificationHandlerRegistry;

    protected ControllerTestBase(ProcessType processType) {
        super(processType);
    }

    protected ControllerTestBase() {
        super();
    }

    protected ModelNode createOperation(String operationName, PathAddress address) {
        ModelNode operation = new ModelNode();
        operation.get(OP).set(operationName);
        if (address.size() > 0) {
            operation.get(OP_ADDR).set(address.toModelNode());
        } else {
            operation.get(OP_ADDR).setEmptyList();
        }

        return operation;
    }

    protected ModelNode createOperation(String operationName) {
        ModelNode operation = new ModelNode();
        operation.get(OP).set(operationName);
        operation.get(OP_ADDR).setEmptyList();
        return operation;
    }

    public ModelNode executeCheckNoFailure(ModelNode operation) throws OperationFailedException {
        ModelNode rsp = getController().execute(operation, null, null, null);
        assertNoUndefinedRolledBackNode(rsp);
        if (FAILED.equals(rsp.get(OUTCOME).asString())) {
            ModelNode fd = rsp.get(FAILURE_DESCRIPTION);
            throw new OperationFailedException(fd.toString(), fd);
        }
        return rsp;
    }

    public ModelNode executeCheckForFailure(ModelNode operation) {
        ModelNode rsp = getController().execute(operation, null, null, null);
        assertNoUndefinedRolledBackNode(rsp);
        if (!FAILED.equals(rsp.get(OUTCOME).asString())) {
            Assert.fail("Should have failed!");
        }
        return rsp;
    }

    @Before
    public void setupController() throws InterruptedException {
        ServiceTarget target = getServiceTarget();
        ModelControllerService svc = createModelControllerService(processType);
        target.addService(ServiceName.of("ModelController")).setInstance(svc).install();
        svc.awaitStartup(30, TimeUnit.SECONDS);
        controller = svc.getValue();
        capabilityRegistry = svc.getCapabilityRegistry();
        notificationHandlerRegistry = svc.getNotificationHandlerRegistry();
        ModelNode setup = Util.getEmptyOperation("setup", new ModelNode());
        controller.execute(setup, null, null, null);
    }

    @After
    public void shutdownServiceContainer() {
        shutdownContainer();
    }

    protected ModelControllerService createModelControllerService(ProcessType processType) {
        return new ModelControllerService(processType);
    }

    protected NotificationHandlerRegistry getNotificationHandlerRegistry() {
        return notificationHandlerRegistry;
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

        public ModelControllerService(final ProcessType processType, ResourceDefinition resourceDefinition){
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
                ControllerTestBase.this.initModel(managementModel);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    static void assertNoUndefinedRolledBackNode(ModelNode response) {
        assertFalse("Response has undefined rolled-back node", response.has(ROLLED_BACK) && !response.hasDefined(ROLLED_BACK));
    }

}
