// Copyright 2008 Google Inc.  All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.enterprise.security.connectors.basicauth;

import com.google.enterprise.connector.spi.AuthenticationResponse;
import com.google.enterprise.connector.spi.RepositoryException;
import com.google.enterprise.connector.spi.AuthenticationIdentity;
import com.google.enterprise.saml.common.GsaConstants.AuthNMechanism;
import com.google.enterprise.security.identity.AuthnDomain;
import com.google.enterprise.security.identity.DomainCredentials;
import com.google.enterprise.security.identity.AuthnDomainGroup;
import com.google.enterprise.security.identity.CredentialsGroup;
import com.google.enterprise.common.MockHttpClient;
import com.google.enterprise.common.HttpClientInterface;
import com.google.enterprise.common.MockHttpTransport;

import junit.framework.TestCase;

import javax.servlet.ServletException;

/*
 * Tests for the {@link BasicAuthConnector} class.
 * Maybe should use a mock Idp...
 */
public class BasicAuthConnectorTest extends TestCase {

  AuthnDomain domain;
  AuthnDomainGroup adg;
  CredentialsGroup cg;
  BasicAuthConnector conn;
  private final HttpClientInterface httpClient;

  public BasicAuthConnectorTest(String name) throws ServletException {
    super(name);
    adg = new AuthnDomainGroup("ADG1");
    domain = new AuthnDomain(
        "BasicDomain", AuthNMechanism.BASIC_AUTH,
        "http://localhost:8973/basic/", adg);
    MockHttpTransport transport = new MockHttpTransport();
    transport.registerServlet(domain.getLoginUrl(), new MockBasicAuthServer.Server1());
    httpClient = new MockHttpClient(transport);
  }

  @Override
  public void setUp() {
    cg = new CredentialsGroup(adg);
  }

  public void testHttpAuthenticate() throws RepositoryException {
    assertTrue(tryCredentials("joe", "plumber").isValid());
    assertFalse(tryCredentials("joe", "biden").isValid());
  }

  private AuthenticationResponse tryCredentials(String username, String password)
      throws RepositoryException {
    BasicAuthConnector conn = new BasicAuthConnector(httpClient, domain.getLoginUrl());
    AuthenticationIdentity id = new DomainCredentials(domain, cg);
    cg.setUsername(username);
    cg.setPassword(password);
    return conn.authenticate(id);
  }
}