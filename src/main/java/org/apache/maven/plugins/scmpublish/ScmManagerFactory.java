/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.maven.plugins.scmpublish;

import javax.inject.Named;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.maven.api.plugin.Log;
import org.apache.maven.api.services.Prompter;
import org.apache.maven.api.services.PrompterException;
import org.apache.maven.scm.manager.BasicScmManager;
import org.apache.maven.scm.manager.ScmManager;
import org.apache.maven.scm.provider.ScmProvider;

/**
 * Builds the {@link ScmManager}. The Maven SCM providers are JSR 330 components published in a Sisu index
 * ({@code META-INF/sisu/javax.inject.Named}); a Maven 4 plugin has no container that reads that index, so the index
 * is read here and every provider found on the plugin class path is registered under its {@code @Named} hint,
 * exactly as the Sisu-backed {@code DefaultScmManager} does.
 */
final class ScmManagerFactory {
    private static final String INDEX = "META-INF/sisu/javax.inject.Named";

    private ScmManagerFactory() {}

    static ScmManager create(ClassLoader classLoader, Prompter prompter, Log log) throws IOException {
        BasicScmManager manager = new BasicScmManager();
        Set<String> hints = new HashSet<>();
        Enumeration<URL> indexes = classLoader.getResources(INDEX);
        while (indexes.hasMoreElements()) {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(indexes.nextElement().openStream(), StandardCharsets.UTF_8))) {
                for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                    String className = line.trim();
                    if (!className.isEmpty()) {
                        register(manager, hints, classLoader, className, prompter, log);
                    }
                }
            }
        }
        return manager;
    }

    private static void register(
            BasicScmManager manager,
            Set<String> hints,
            ClassLoader classLoader,
            String className,
            Prompter prompter,
            Log log) {
        try {
            Class<?> type = Class.forName(className, false, classLoader);
            if (!ScmProvider.class.isAssignableFrom(type)) {
                return;
            }
            Named named = type.getAnnotation(Named.class);
            if (named == null || named.value().isEmpty()) {
                return;
            }
            // like Sisu, the first provider registered for a hint wins
            if (hints.add(named.value())) {
                manager.setScmProvider(named.value(), instantiate(type.asSubclass(ScmProvider.class), prompter));
                log.debug("Registered SCM provider '" + named.value() + "': " + className);
            }
        } catch (ReflectiveOperationException | LinkageError e) {
            log.warn("Unable to load SCM provider " + className + ": " + e);
        }
    }

    private static ScmProvider instantiate(Class<? extends ScmProvider> type, Prompter prompter)
            throws ReflectiveOperationException {
        for (Constructor<?> constructor : type.getConstructors()) {
            Class<?>[] parameters = constructor.getParameterTypes();
            if (parameters.length == 0) {
                return (ScmProvider) constructor.newInstance();
            }
            if (parameters.length == 1
                    && parameters[0].getName().equals("org.codehaus.plexus.components.interactivity.Prompter")) {
                return (ScmProvider) constructor.newInstance(new PlexusPrompterAdapter(prompter));
            }
        }
        throw new NoSuchMethodException("No supported constructor in " + type.getName());
    }

    /**
     * Presents the Maven 4 {@link Prompter} service as the Plexus {@code Prompter} the jgit provider asks for.
     */
    private static final class PlexusPrompterAdapter implements org.codehaus.plexus.components.interactivity.Prompter {
        private final Prompter prompter;

        PlexusPrompterAdapter(Prompter prompter) {
            this.prompter = prompter;
        }

        @Override
        public String prompt(String message) throws org.codehaus.plexus.components.interactivity.PrompterException {
            return prompt(message, null, null);
        }

        @Override
        public String prompt(String message, String defaultReply)
                throws org.codehaus.plexus.components.interactivity.PrompterException {
            return prompt(message, null, defaultReply);
        }

        @Override
        public String prompt(String message, List<String> possibleValues)
                throws org.codehaus.plexus.components.interactivity.PrompterException {
            return prompt(message, possibleValues, null);
        }

        @Override
        public String prompt(String message, List<String> possibleValues, String defaultReply)
                throws org.codehaus.plexus.components.interactivity.PrompterException {
            try {
                return prompter.prompt(message, possibleValues, defaultReply);
            } catch (PrompterException e) {
                throw new org.codehaus.plexus.components.interactivity.PrompterException(e.getMessage(), e);
            }
        }

        @Override
        public String promptForPassword(String message)
                throws org.codehaus.plexus.components.interactivity.PrompterException {
            try {
                return prompter.promptForPassword(message);
            } catch (PrompterException e) {
                throw new org.codehaus.plexus.components.interactivity.PrompterException(e.getMessage(), e);
            }
        }

        @Override
        public void showMessage(String message) throws org.codehaus.plexus.components.interactivity.PrompterException {
            try {
                prompter.showMessage(message);
            } catch (PrompterException e) {
                throw new org.codehaus.plexus.components.interactivity.PrompterException(e.getMessage(), e);
            }
        }
    }
}
