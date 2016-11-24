/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2012, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.as.domain.management.security;

import static org.jboss.as.domain.management.logging.DomainManagementLogger.SECURITY_LOGGER;

import java.io.IOException;
import java.security.Principal;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.UnsupportedCallbackException;
import javax.security.sasl.AuthorizeCallback;

import org.jboss.as.domain.management.AuthMechanism;
import org.jboss.as.domain.management.SecurityRealm;
import org.jboss.msc.service.Service;
import org.jboss.msc.service.ServiceName;
import org.jboss.msc.service.StartContext;
import org.jboss.msc.service.StartException;
import org.jboss.msc.service.StopContext;
import org.wildfly.security.auth.SupportLevel;
import org.wildfly.security.auth.server.IdentityLocator;
import org.wildfly.security.auth.server.RealmIdentity;
import org.wildfly.security.auth.server.RealmUnavailableException;
import org.wildfly.security.credential.Credential;
import org.wildfly.security.evidence.Evidence;
import org.wildfly.security.evidence.X509PeerCertificateChainEvidence;

/**
 * A CallbackHandler for Client Cert authentication. Currently no Callbacks are supported but later this may be expanded for
 * additional verification.
 *
 * @author <a href="mailto:darran.lofthouse@jboss.com">Darran Lofthouse</a>
 */
public class ClientCertCallbackHandler implements Service<CallbackHandlerService>, CallbackHandlerService, CallbackHandler {

    private static final String SERVICE_SUFFIX = "client_cert";

    ClientCertCallbackHandler() {
    }

    /*
     * Service Methods
     */

    public CallbackHandlerService getValue() throws IllegalStateException, IllegalArgumentException {
        return this;
    }

    public void start(StartContext context) throws StartException {
    }

    public void stop(StopContext context) {
    }

    /*
     * CallbackHandlerService Methods
     */

    public AuthMechanism getPreferredMechanism() {
        return AuthMechanism.CLIENT_CERT;
    }

    public Set<AuthMechanism> getSupplementaryMechanisms() {
        return Collections.emptySet();
    }

    public Map<String, String> getConfigurationOptions() {
        return Collections.emptyMap();
    }

    @Override
    public boolean isReadyForHttpChallenge() {
        // Whilst this CallbackHandlerService may be ready to authenticate it is not
        // involved in challenge based authentication.
        return false;
    }

    public CallbackHandler getCallbackHandler(Map<String, Object> sharedState) {
        return this;
    }

    @Override
    public org.wildfly.security.auth.server.SecurityRealm getElytronSecurityRealm() {
        return new ClientCertSecurityRealm();
    }

    public void handle(Callback[] callbacks) throws IOException, UnsupportedCallbackException {
        for (Callback current : callbacks) {
            if (current instanceof AuthorizeCallback) {
                AuthorizeCallback acb = (AuthorizeCallback) current;
                boolean authorized = acb.getAuthenticationID().equals(acb.getAuthorizationID());
                if (authorized == false) {
                    SECURITY_LOGGER.tracef(
                            "Checking 'AuthorizeCallback', authorized=false, authenticationID=%s, authorizationID=%s.",
                            acb.getAuthenticationID(), acb.getAuthorizationID());
                }
                acb.setAuthorized(authorized);
            } else {
                throw new UnsupportedCallbackException(current);
            }
        }
    }

    private class ClientCertSecurityRealm implements org.wildfly.security.auth.server.SecurityRealm {

        @Override
        public RealmIdentity getRealmIdentity(IdentityLocator locator) throws RealmUnavailableException {
            Evidence evidence = locator.getEvidence();
            if (evidence instanceof X509PeerCertificateChainEvidence) {
                return new ClientCertRealmIdentity(((X509PeerCertificateChainEvidence)evidence).getPrincipal());
            }

            return RealmIdentity.NON_EXISTENT;
        }

        @Override
        public SupportLevel getCredentialAcquireSupport(Class<? extends Credential> credentialType, String algorithmName)
                throws RealmUnavailableException {
            return SupportLevel.UNSUPPORTED;
        }

        @Override
        public SupportLevel getEvidenceVerifySupport(Class<? extends Evidence> evidenceType, String algorithmName)
                throws RealmUnavailableException {
            return X509PeerCertificateChainEvidence.class.isAssignableFrom(evidenceType) ? SupportLevel.SUPPORTED : SupportLevel.UNSUPPORTED;
        }


        private class ClientCertRealmIdentity implements RealmIdentity {

            private final Principal principal;

            ClientCertRealmIdentity(final Principal principal) {
                this.principal = principal;
            }

            @Override
            public Principal getRealmIdentityPrincipal() {
                return principal;
            }

            @Override
            public SupportLevel getCredentialAcquireSupport(Class<? extends Credential> credentialType, String algorithmName)
                    throws RealmUnavailableException {
                return ClientCertSecurityRealm.this.getCredentialAcquireSupport(credentialType, algorithmName);
            }

            @Override
            public <C extends Credential> C getCredential(Class<C> credentialType) throws RealmUnavailableException {
                return null;
            }

            @Override
            public SupportLevel getEvidenceVerifySupport(Class<? extends Evidence> evidenceType, String algorithmName)
                    throws RealmUnavailableException {
                return ClientCertSecurityRealm.this.getEvidenceVerifySupport(evidenceType, algorithmName);
            }

            @Override
            public boolean verifyEvidence(Evidence evidence) throws RealmUnavailableException {
                // Within legacy security realms we rely on the trust manager check on establishing the connection and trust all trusted users.
                return evidence instanceof X509PeerCertificateChainEvidence;
            }

            @Override
            public boolean exists() throws RealmUnavailableException {
                return true;
            }

        }

    }

    public static final class ServiceUtil {

        private ServiceUtil() {
        }

        public static ServiceName createServiceName(final String realmName) {
            return SecurityRealm.ServiceUtil.createServiceName(realmName).append(SERVICE_SUFFIX);
        }
    }
}
