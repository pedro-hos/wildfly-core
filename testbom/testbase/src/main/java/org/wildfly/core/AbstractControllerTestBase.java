/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.wildfly.core;

import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.FAILED;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.FAILURE_DESCRIPTION;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OP;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OP_ADDR;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OUTCOME;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.RESULT;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.jboss.as.controller.ManagementModel;
import org.jboss.as.controller.ModelController;
import org.jboss.as.controller.OperationContext;
import org.jboss.as.controller.OperationFailedException;
import org.jboss.as.controller.PathAddress;
import org.jboss.as.controller.PathElement;
import org.jboss.as.controller.ProcessType;
import org.jboss.as.controller.audit.AuditLogger;
import org.jboss.as.controller.audit.ManagedAuditLogger;
import org.jboss.as.controller.persistence.AbstractConfigurationPersister;
import org.jboss.as.controller.persistence.ModelMarshallingContext;
import org.jboss.as.controller.registry.ManagementResourceRegistration;
import org.jboss.as.controller.registry.Resource;
import org.jboss.dmr.ModelNode;
import org.jboss.dmr.ModelType;
import org.jboss.msc.service.ServiceContainer;
import org.jboss.msc.service.ServiceTarget;
import org.jboss.staxmapper.XMLElementWriter;
import org.junit.Assert;

/**
 * @author pesilva@redhat.com
 */
public abstract class AbstractControllerTestBase {

    protected ServiceContainer container;
    protected ModelController controller;
    protected volatile ProcessType processType;

    protected AbstractControllerTestBase() {
        this(ProcessType.EMBEDDED_SERVER);
    }

    protected AbstractControllerTestBase(ProcessType processType) {
        this.processType = processType;
    }

    public abstract void setupController() throws InterruptedException;
    public abstract void shutdownServiceContainer();
    protected abstract void initModel(ManagementModel managementModel);

    protected void addBootOperations(List<ModelNode> bootOperations) {}

    protected ManagedAuditLogger getAuditLogger(){
        return AuditLogger.NO_OP_LOGGER;
    }

    protected ServiceTarget getServiceTarget() {
        container = ServiceContainer.Factory.create("test");
        return container.subTarget();
    }

    public void shutdownContainer() {
        if (container != null) {
            container.shutdown();
            try {
                container.awaitTermination(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                e.printStackTrace();
            } finally {
                container = null;
            }
        }
    }

    protected ModelNode createOperation(String operationName, String... address) {
        ModelNode operation = new ModelNode();
        operation.get(OP).set(operationName);
        if (address.length > 0) {
            for (String addr : address) {
                operation.get(OP_ADDR).add(addr);
            }
        } else {
            operation.get(OP_ADDR).setEmptyList();
        }

        return operation;
    }

    public ModelNode executeForResult(ModelNode operation) throws OperationFailedException {
        ModelNode rsp = getController().execute(operation, null, null, null);
        if (FAILED.equals(rsp.get(OUTCOME).asString())) {
            ModelNode fd = rsp.get(FAILURE_DESCRIPTION);
            throw new OperationFailedException(fd.toString(), fd);
        }
        return rsp.get(RESULT);
    }

    public void executeForFailure(ModelNode operation) {
        try {
            executeForResult(operation);
            Assert.fail("Should have given error");
        } catch (OperationFailedException expected) {
            // ignore
        }
    }

    public ModelController getController() {
        return controller;
    }

    public ServiceContainer getContainer() {
        return container;
    }

    public static void createModel(final OperationContext context, final ModelNode node) {
        createModel(context, PathAddress.EMPTY_ADDRESS, node);
    }

    static void createModel(final OperationContext context, final PathAddress base, final ModelNode node) {
        if (!node.isDefined()) {
            return;
        }
        final ManagementResourceRegistration registration = context.getResourceRegistrationForUpdate();
        final Set<String> children = registration.getChildNames(base);
        final ModelNode current = new ModelNode();
        final Resource resource = base.size() == 0 ? context.readResourceForUpdate(PathAddress.EMPTY_ADDRESS) : context.createResource(base);
        if (node.getType() == ModelType.OBJECT) {
            for (final String key : node.keys()) {
                if (!children.contains(key)) {
                    current.get(key).set(node.get(key));
                }
            }
            resource.getModel().set(current);
        } else {
            resource.getModel().set(node);
            return;
        }
        if (children != null && !children.isEmpty()) {
            for (final String childType : children) {
                if (node.hasDefined(childType)) {
                    for (final String key : node.get(childType).keys()) {
                        createModel(context, base.append(PathElement.pathElement(childType, key)), node.get(childType, key));
                    }
                }
            }
        }
    }

    public static class EmptyConfigurationPersister extends AbstractConfigurationPersister {

        public EmptyConfigurationPersister() {
            super(null);
        }

        public EmptyConfigurationPersister(XMLElementWriter<ModelMarshallingContext> rootDeparser) {
            super(rootDeparser);
        }

        @Override
        public PersistenceResource store(final ModelNode model, Set<PathAddress> affectedAddresses) {
            return NullPersistenceResource.INSTANCE;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public List<ModelNode> load() {
            return new ArrayList<ModelNode>();
        }

        private static class NullPersistenceResource implements PersistenceResource {

            private static final NullPersistenceResource INSTANCE = new NullPersistenceResource();

            @Override
            public void commit() {
            }

            @Override
            public void rollback() {
            }
        }
    }

}
