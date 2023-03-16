package hu.blackbelt.jaxrs;

/*-
 * #%L
 * CXF JAX-RS application manager
 * %%
 * Copyright (C) 2018 - 2023 BlackBelt Technology
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import lombok.extern.slf4j.Slf4j;
import org.osgi.framework.*;
import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.component.annotations.*;

import javax.ws.rs.core.Application;
import java.util.*;

@Component(immediate = true, service = ApplicationManager.class)
@Slf4j
public class ApplicationManager {

    public static final String GENERATED_BY_KEY = "__generated.by";
    public static final String GENERATED_BY_VALUE = UUID.randomUUID().toString();

    // define target filter in configuration in case of multiple JAX-RS implementations
    @Reference(target = "(" + ServerManager.ALIAS_KEY + "=" + CxfServerManager.ALIAS_VALUE + ")")
    private ServerManager serverManager;

    @Reference(policyOption = ReferencePolicyOption.GREEDY)
    private ConfigurationAdmin configAdmin;

    private ApplicationStore applicationStore;
    private SharedProviderStore sharedProviderStore;

    @Activate
    void start(final BundleContext context) {
        sharedProviderStore = new SharedProviderStore(context, new SharedProviderCallback());
        applicationStore = new ApplicationStore(context, configAdmin, new ApplicationProviderCallback());

        sharedProviderStore.start();
        applicationStore.start();
    }

    @Modified
    void update() {
        // do not restart application manager
    }

    @Deactivate
    void stop() {
        if (sharedProviderStore != null) {
            sharedProviderStore.stop();
            sharedProviderStore = null;
        }
        if (applicationStore != null) {
            applicationStore.stop();
            applicationStore = null;
        }
        serverManager.shutdown();
    }

    private List<Object> getSingleApplicationProviders(final Long applicationId) {
        final List<Object> providers = new LinkedList<>();
        if (sharedProviderStore != null) {
            providers.addAll(sharedProviderStore.getProviders(applicationId));
        }
        if (applicationStore != null) {
            providers.addAll(applicationStore.getProviders(applicationId));
        }
        return Collections.unmodifiableList(providers);
    }

    private Map<Long, List<Object>> getApplicationProviders(Collection<Long> applicationIds) {
        final Map<Long, List<Object>> providers = new HashMap<>();

        if (applicationIds == null && applicationStore != null) {
            // collect providers for all applications
            applicationIds = applicationStore.getApplicationIds();
        } else if (applicationIds == null) {
            applicationIds = Collections.emptySet();
        }

        applicationIds.forEach(applicationId -> providers.put(applicationId, getSingleApplicationProviders(applicationId)));

        return providers;
    }

    class SharedProviderCallback implements SharedProviderStore.Callback {

        @Override
        public void restartApplications(final Collection<Long> applicationIds) {
            if (applicationIds != null) {
                serverManager.restartApplications(applicationIds, getApplicationProviders(applicationIds));
            } else {
                serverManager.restartAllApplications(getApplicationProviders(null));
            }
        }
    }

    class ApplicationProviderCallback implements ApplicationStore.Callback {
        @Override
        public void addApplication(final Long applicationId) {
            if (sharedProviderStore != null) {
                sharedProviderStore.addApplication(applicationId);
            }
        }

        @Override
        public void removeApplication(final Long applicationId) {
            if (sharedProviderStore != null) {
                sharedProviderStore.removeApplication(applicationId);
            }
        }

        @Override
        public void startApplication(final Long applicationId, final Application application, final Bundle applicationBundle) {
            serverManager.startApplication(applicationId, application, applicationBundle, getSingleApplicationProviders(applicationId));
        }

        @Override
        public void stopApplication(final Long applicationId) {
            serverManager.stopApplication(applicationId);
        }

        @Override
        public void restartApplications(final Collection<Long> applicationIds) {
            if (applicationIds != null) {
                serverManager.restartApplications(applicationIds, getApplicationProviders(applicationIds));
            } else {
                serverManager.restartAllApplications(getApplicationProviders(null));
            }
        }

        @Override
        public void updateApplicationResources(final Long applicationId, final Application application) {
            serverManager.updateApplicationResources(applicationId, application, getSingleApplicationProviders(applicationId));
        }
    }
}
