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

import org.osgi.framework.Bundle;

import javax.ws.rs.core.Application;
import java.util.*;

interface ServerManager {
    String ALIAS_KEY = "alias";

    void startApplication(Long applicationId, Application application, Bundle applicationBundle, List<Object> providers);

    Application stopApplication(Long applicationId);

    void updateApplicationResources(Long applicationId, Application application, List<Object> providers);

    void restartApplications(Collection<Long> applicationIds, Map<Long, List<Object>> providers);

    void restartAllApplications(Map<Long, List<Object>> providers);

    void shutdown();
}
