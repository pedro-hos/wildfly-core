/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.jboss.as.jmx.test.util;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.jboss.as.controller.ControlledProcessState;
import org.jboss.as.controller.ManagementModel;
import org.jboss.as.controller.ModelController;
import org.jboss.as.controller.PathElement;
import org.jboss.as.controller.ProcessType;
import org.jboss.as.controller.ResourceBuilder;
import org.jboss.as.controller.access.management.DelegatingConfigurableAuthorizer;
import org.jboss.as.controller.audit.ManagedAuditLogger;
import org.jboss.as.controller.descriptions.NonResolvingResourceDescriptionResolver;
import org.jboss.as.controller.persistence.ConfigurationPersistenceException;
import org.jboss.as.controller.registry.Resource;
import org.jboss.as.server.Services;
import org.jboss.dmr.ModelNode;
import org.jboss.msc.service.ServiceBuilder;
import org.jboss.msc.service.ServiceTarget;
import org.junit.After;
import org.junit.Before;
import org.wildfly.security.auth.server.SecurityIdentity;
import org.wildfly.test.controller.base.AbstractControllerTestBase;

/**
 * @author Emanuel Muckenhuber
 */
public abstract class JMXControllerTestBase extends AbstractControllerTestBase {

    protected JMXControllerTestBase() {
        super();
    }

    protected JMXControllerTestBase(ProcessType processType) {
        super(processType);
    }

    @Before
    public void setupController() throws InterruptedException {
        ServiceTarget target = getServiceTarget();
        ModelControllerService svc = new ModelControllerService(processType, getAuditLogger(), getAuthorizer());
        ServiceBuilder<ModelController> builder = target.addService(Services.JBOSS_SERVER_CONTROLLER, svc);
        builder.install();
        svc.awaitStartup(30, TimeUnit.SECONDS);
        controller = svc.getValue();
    }

    @After
    public void shutdownServiceContainer() {
        shutdownContainer();
    }

    protected DelegatingConfigurableAuthorizer getAuthorizer() {
        return null;
    }

    protected Supplier<SecurityIdentity> getSecurityIdentitySupplier() {
        return null;
    }

    protected class ModelControllerService extends TestModelControllerService {

        ModelControllerService(final ProcessType processType, final ManagedAuditLogger auditLogger, final DelegatingConfigurableAuthorizer authorizer) {
            super(processType, new EmptyConfigurationPersister(), new ControlledProcessState(true),
                    ResourceBuilder.Factory.create(PathElement.pathElement("root"), NonResolvingResourceDescriptionResolver.INSTANCE).build(),
                    auditLogger, authorizer);
        }

        @Override
        protected boolean boot(List<ModelNode> bootOperations, boolean rollbackOnRuntimeFailure)
                throws ConfigurationPersistenceException {
            addBootOperations(bootOperations);
            return super.boot(bootOperations, rollbackOnRuntimeFailure);
        }

        protected void initModel(ManagementModel managementModel, Resource modelControllerResource) {
            try {
                JMXControllerTestBase.this.initModel(managementModel);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
}
