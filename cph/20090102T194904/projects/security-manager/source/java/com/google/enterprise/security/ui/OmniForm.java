// Copyright (C) 2008 Google Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.enterprise.security.ui;

import com.google.enterprise.security.identity.AuthnDomainGroup;
import com.google.enterprise.security.identity.CredentialsGroup;
import com.google.enterprise.saml.server.BackEnd;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;
import java.util.Collection;
import java.util.logging.Logger;
import java.io.IOException;

import javax.servlet.http.HttpServletRequest;

/**
 * This class implements the Omniform processing logic.  Its primary job is to
 * translate the user input into valid CredentialsGroups.
 *
 * The OmniForm is also the class that contains the state of the user's login
 * session.
 */
public class OmniForm {

  private static final
  Logger LOGGER = Logger.getLogger(OmniForm.class.getName());

  OmniFormHtml omniHtml;
  BackEnd backend;

  List<FormElement> formElements;
  Map<FormElement,CredentialsGroup> formElemToCG;


  /**
   * TODO: ideally instead of OmniFormHtml we would pass in a UI interface.
   * I haven't figured out the right way to do so and keep the generate/submit
   * methods consistent for all UI implementations.  Perhaps we must restrict
   * UI implementations to ones that can be returned as HTTP content. 
   * @throws IOException 
   */
  public OmniForm(BackEnd backend, OmniFormHtml omniFormHtml) throws IOException {
    this.omniHtml = omniFormHtml;
    this.backend = backend;
    this.formElements = new ArrayList<FormElement>();
    this.formElemToCG = new HashMap<FormElement,CredentialsGroup>();

    // TODO: check user cookies. Tricky part: if a user has a forms cookie
    // that shares the CG of a basic_auth site, we still need their login info.
    // TODO: check the SessionManager for the user's credentials/login info ?    
    for (AuthnDomainGroup adg : backend.getAuthnDomainGroups()) {
      FormElement formElem = new FormElement(adg.getHumanName());
      formElements.add(formElem);
      CredentialsGroup cg = new CredentialsGroup(adg);
      cg.autogenerateDomainCredentials();
      formElemToCG.put(formElem, cg);      
    }
  }

  /**
   * The ordering in which the CredentialsGroups of this OmniForm are
   * are returned is unspecified.
   */
  public Collection<CredentialsGroup> getCredentialsGroups() {
    return formElemToCG.values();
  }

  /**
   * Generate a login form that reflects the current state of this OmniForm.
   * @return an html string
   */
  public String generateForm() {
    return omniHtml.generateForm(formElements);
  }

  /**
   * Handles a submit of an OmniForm (a POST).  This method will attempt to
   * perform authentication on the user-provided login information and update
   * the CredentialsGroups of this OmniForm based on the results.
   *
   * This method assumes that the provided request is a POST of a form generated
   * by the same instance of this class.
   *
   * @param request
   * @return a username of one of the credentials groups verified by the
   * authentication step of this method
   */
  public String handleFormSubmit(HttpServletRequest request) {
    omniHtml.parsePostedForm(request, formElements);
    updateCredentials(formElements);

    String username = null;
    for (CredentialsGroup cg : getCredentialsGroups()) {
      if (!cg.allCredentialsFilled()) {
        LOGGER.info("Credentials group unfulfilled: " + cg.getAuthnDomainGroup().getHumanName());
        return null;
      }
      username = cg.getUsername();
    }

    return username;
  }

  private void updateCredentials(List<FormElement> formElements) {
    for (FormElement elem : formElements) {
      if (elem.isActive()) {
        CredentialsGroup cg = formElemToCG.get(elem);
        cg.setUsername(elem.getUsername());
        cg.setPassword(elem.getPassword());
        backend.authenticate(cg);
        if (cg.allCredentialsFilled()) {
          elem.setActive(false);
        }
      }
    }
  }
}