/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.jboss.as.domain.management.security.util;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.jboss.as.controller.AbstractControllerTestBase;
import org.jboss.as.controller.ControlledProcessState;
import org.jboss.as.controller.ManagementModel;
import org.jboss.as.controller.ModelController;
import org.jboss.as.controller.PathElement;
import org.jboss.as.controller.ProcessType;
import org.jboss.as.controller.ResourceBuilder;
import org.jboss.as.controller.audit.ManagedAuditLogger;
import org.jboss.as.controller.descriptions.NonResolvingResourceDescriptionResolver;
import org.jboss.as.controller.persistence.ConfigurationPersistenceException;
import org.jboss.as.controller.registry.Resource;
import org.jboss.dmr.ModelNode;
import org.jboss.msc.service.ServiceBuilder;
import org.jboss.msc.service.ServiceName;
import org.jboss.msc.service.ServiceTarget;
import org.junit.After;
import org.junit.Before;

/**
 * @author Emanuel Muckenhuber
 */
public abstract class DomainManagementControllerTestBase extends AbstractControllerTestBase {

    protected DomainManagementControllerTestBase() {
        super();
    }

    @Before
    public void setupController() throws InterruptedException {
        ServiceTarget target = getServiceTarget();
        ModelControllerService svc = new ModelControllerService(processType, getAuditLogger());
        ServiceBuilder<ModelController> builder = target.addService(ServiceName.of("ModelController"), svc);
        builder.install();
        svc.awaitStartup(30, TimeUnit.SECONDS);
        controller = svc.getValue();
    }

    @After
    public void shutdownServiceContainer() {
       super.shutdownContainer();
    }

    class ModelControllerService extends TestModelControllerService {

        ModelControllerService(final ProcessType processType, final ManagedAuditLogger auditLogger) {
            super(processType, new EmptyConfigurationPersister(), new ControlledProcessState(true),
                    ResourceBuilder.Factory.create(PathElement.pathElement("root"), NonResolvingResourceDescriptionResolver.INSTANCE).build(),
                    auditLogger);
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
                DomainManagementControllerTestBase.this.initModel(managementModel);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
}
