// Copyright 2011 Google Inc.
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

package com.google.enterprise.connector.servlet;

import com.google.common.base.Strings;
import com.google.enterprise.connector.common.StringUtils;
import com.google.enterprise.connector.instantiator.MockInstantiator;
import com.google.enterprise.connector.instantiator.ThreadPool;
import com.google.enterprise.connector.manager.Context;
import com.google.enterprise.connector.manager.Manager;
import com.google.enterprise.connector.manager.MockManager;
import com.google.enterprise.connector.manager.ProductionManager;
import com.google.enterprise.connector.persist.ConnectorNotFoundException;
import com.google.enterprise.connector.pusher.MockFeedConnection;
import com.google.enterprise.connector.spi.Document;
import com.google.enterprise.connector.spi.DocumentAccessException;
import com.google.enterprise.connector.spi.DocumentNotFoundException;
import com.google.enterprise.connector.spi.MockConnector;
import com.google.enterprise.connector.spi.MockRetriever;
import com.google.enterprise.connector.spi.SpiConstants;
import com.google.enterprise.connector.spi.Value;
import com.google.enterprise.connector.test.ConnectorTestUtils;
import com.google.enterprise.connector.util.SystemClock;

import junit.framework.TestCase;

import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import java.util.Collections;
import java.util.Map;
import java.util.logging.Logger;
import java.util.zip.GZIPInputStream;

/**
 * Tests {@link GetDocumentContent} servlet class.
 */
public class GetDocumentContentTest extends TestCase {
  private static final Logger LOGGER =
      Logger.getLogger(GetDocumentContentTest.class.getName());

  protected void setUp() {
    // A non-null contentUrlPrefix.
    Context.getInstance().setContentUrlPrefix("http://goo/");

    // We're comparing date strings here, so we need a fixed time zone.
    Value.setFeedTimeZone("GMT-08:00");
  }

  /** Test basic servlet function against a MockManager. */
  public void testGetDocumentContentMockManager() throws Exception {
    Manager manager = MockManager.getInstance();
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    int status;

    // Connector1 serves up document content equal to docid.
    status = GetDocumentContent.handleDoGet(manager, "connector1",
                                            "xyzzy", buffer);
    assertEquals(200, status);
    assertEquals("xyzzy", buffer.toString());

    // Connector2 serves up no content at all.
    buffer.reset();
    status = GetDocumentContent.handleDoGet(manager, "connector2",
                                            "xyzzy", buffer);
    assertEquals(200, status);
    assertEquals(0, buffer.size());

    // UnknownConnector does not exist.
    buffer.reset();
    status = GetDocumentContent.handleDoGet(manager, "unknownConnector",
                                            "xyzzy", buffer);
    assertEquals(503, status);
    assertEquals(0, buffer.size());
  }

  /** Test basic servlet function against a MockManager. */
  public void testGetLastModifiedMockManager() throws Exception {
    Manager manager = MockManager.getInstance();
    long lastModified;

    // Connector1 has a lastModifiedDate
    // xyzzy has metadata.
    Document metaData = GetDocumentContent.getDocumentMetaData(
        createMockRequest(), manager, "connector1", "xyzzy");
    assertNotNull(metaData);
    lastModified = GetDocumentContent.handleGetLastModified(metaData);
    assertEquals(3600 * 1000, lastModified);

    // Connector2 has no lastModifiedDate
    metaData = GetDocumentContent.getDocumentMetaData(
        createMockRequest(), manager, "connector2", "xyzzy");
    assertNotNull(metaData);
    lastModified = GetDocumentContent.handleGetLastModified(metaData);
    assertEquals(-1L, lastModified);
  }

  /** Test getDocumentMetaData against a MockManager. */
  public void testGetDocumentMetaDataMockManager() throws Exception {
    Manager manager = MockManager.getInstance();
    MockHttpServletRequest req = createMockRequest();
    
    // xyzzy has metadata.
    Document metaData = GetDocumentContent.getDocumentMetaData(req,
        manager, "connector1", "xyzzy");
    assertNotNull(metaData);
    // Test cache.
    assertSame(metaData, GetDocumentContent.getDocumentMetaData(req,
        manager, "connector1", "xyzzy"));

    req = createMockRequest();
    // UnknownConnector does not exist.
    try {
      GetDocumentContent.getDocumentMetaData(req,
          manager, "unknownConnector", "xyzzy");
      fail("Expected ConnectorNotFoundException, but got none.");
    } catch (ConnectorNotFoundException expected) {
      // Expected.
    }
    // Test cache is empty.
    try {
      GetDocumentContent.getDocumentMetaData(req,
          manager, "unknownConnector", "xyzzy");
      fail("Expected ConnectorNotFoundException, but got none.");
    } catch (ConnectorNotFoundException expected) {
      // Expected.
    }
  }

  /**
   * Test handleGetContentLength.
   */
  public void testGetContentLength() throws Exception {
    Map<String, Object> props =
        ConnectorTestUtils.createSimpleDocumentBasicProperties("test");

    // No contentLength.
    testGetContentLength(props, null);

    // Invalid contentLength.
    props.put(SpiConstants.PROPNAME_CONTENT_LENGTH, "foo");
    testGetContentLength(props, null);

    // Negative lengths are not allowed.
    props.put(SpiConstants.PROPNAME_CONTENT_LENGTH, new Long(-1L));
    testGetContentLength(props, null);

    // Too big contentLength.
    props.put(SpiConstants.PROPNAME_CONTENT_LENGTH,
              new Long(1L + Integer.MAX_VALUE));
    testGetContentLength(props, null);

    // Acceptable contentLength.
    props.put(SpiConstants.PROPNAME_CONTENT_LENGTH,
              new Long(1000));
    testGetContentLength(props, 1000);    

    // Acceptable contentLength.
    props.put(SpiConstants.PROPNAME_CONTENT_LENGTH,
              new Long(Integer.MAX_VALUE));
    testGetContentLength(props, Integer.MAX_VALUE);    
  }

  private void testGetContentLength(Map<String, Object> props,
      Integer expected) throws Exception {
    Document metadata = ConnectorTestUtils.createSimpleDocument(props);
    assertEquals(expected, GetDocumentContent.handleGetContentLength(metadata));
  }

  private String connectorName = MockInstantiator.TRAVERSER_NAME1;
  private String docid = "docid";

  private MockInstantiator getMockInstantiator() throws Exception {
    MockInstantiator instantiator =
        new MockInstantiator(new ThreadPool(5, new SystemClock()));
    instantiator.setupTestTraversers();
    instantiator.addConnector(connectorName,
        new MockConnector(null, null, null, new MockRetriever(), null));
    return instantiator;
  }

  private Manager getProductionManager() throws Exception {
    MockInstantiator instantiator = getMockInstantiator();
    ProductionManager manager = new ProductionManager();
    manager.setInstantiator(instantiator);
    manager.setFeedConnection(new MockFeedConnection());
    return manager;
  }

  private void patchRealProductionManager() throws Exception {
    MockInstantiator instantiator = getMockInstantiator();
    assertTrue(Context.getInstance().getManager() instanceof ProductionManager);
    ProductionManager manager =
        (ProductionManager) (Context.getInstance().getManager());
    manager.setInstantiator(instantiator);
  }

  /** Test ProductionManager getDocumentContent. */
  private void checkGetDocumentContent(String connectorName, String docid,
      int expectedStatus, String expectedOutput) throws Exception {
    Manager manager = getProductionManager();
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    int status =
        GetDocumentContent.handleDoGet(manager, connectorName, docid, buffer);

    assertEquals(expectedStatus, status);
    assertEquals(expectedOutput, buffer.toString());
  }

  /** Test ProductionManager getDocumentContent with null connectorName. */
  public void testGetDocumentContentNullConnectorName() throws Exception {
    checkNullOrEmptyConnectorNameDocid(null, docid);
  }

  /** Test ProductionManager getDocumentContent with empty connectorName. */
  public void testGetDocumentContentEmptyConnectorName() throws Exception {
    checkNullOrEmptyConnectorNameDocid("", docid);
  }

  /** Test ProductionManager getDocumentContent with null docid. */
  public void testGetDocumentContentNullDocid() throws Exception {
    checkNullOrEmptyConnectorNameDocid(connectorName, null);
  }

  /** Test ProductionManager getDocumentContent with empty docid. */
  public void testGetDocumentContentEmptyDocid() throws Exception {
    checkNullOrEmptyConnectorNameDocid(connectorName, "");
  }

  private void checkNullOrEmptyConnectorNameDocid(String connectorName,
      String docid) throws Exception {
    patchRealProductionManager();
    MockHttpServletRequest req = createMockRequest(connectorName, docid);
    MockHttpServletResponse res = new MockHttpServletResponse();
    new GetDocumentContent().service(req, res);
    assertEquals(400, res.getStatus());
  }    

  /** Test ProductionManager getDocumentContent with ConnectorNotFound. */
  public void testGetDocumentContentConnectorNotFound() throws Exception {
    checkGetDocumentContent("unknownConnector", docid, 503, "");
  }

  /** Test ProductionManager getDocumentContent with Document NotFound. */
  public void testGetDocumentContentDocumentNotFound() throws Exception {
    checkGetDocumentContent(connectorName, MockRetriever.DOCID_NOT_FOUND,
                            404, "");
  }

  /** Test ProductionManager getDocumentContent with Document Access Denied. */
  public void testGetDocumentContentDocumentNoAccess() throws Exception {
    checkGetDocumentContent(connectorName, MockRetriever.DOCID_NO_ACCESS,
                            403, "");
  }

  /** Test ProductionManager getDocumentContent with RepositoryException. */
  public void testGetDocumentContentRepositoryException() throws Exception {
    checkGetDocumentContent(connectorName,
        MockRetriever.DOCID_REPOSITORY_EXCEPTION, 503, "");
  }

  /** Test ProductionManager getDocumentContent where document has no content. */
  public void testGetDocumentContentNoContent() throws Exception {
    // GSA still doesn't handle docs with no content, so the
    // Production Manager substitutes a single space.
    checkGetDocumentContent(connectorName,
        MockRetriever.DOCID_NO_CONTENT, 200, " ");
  }

  /** Test ProductionManager getDocumentContent where document has empty content. */
  public void testGetDocumentContentEmptyContent() throws Exception {
    // GSA still doesn't handle docs with no content, so the
    // Production Manager substitutes a single space.
    checkGetDocumentContent(connectorName,
        MockRetriever.DOCID_EMPTY_CONTENT, 200, " ");
  }

  /** Test ProductionManager getDocumentContent. */
  public void testGetDocumentContent() throws Exception {
    checkGetDocumentContent(connectorName, docid, 200, docid);
  }

  private MockHttpServletRequest createMockRequest() {
    return new MockHttpServletRequest("GET",
        "/connector-manager/getDocumentContent");
  }

  private MockHttpServletRequest createMockRequest(
      String connectorName, String docid) throws Exception {
    MockHttpServletRequest req = createMockRequest();
    req.setParameter(ServletUtil.XMLTAG_CONNECTOR_NAME, connectorName);
    req.setParameter(ServletUtil.QUERY_PARAM_DOCID, docid);
    encodeQueryParameter(req);
    return req;
  }

  /** Test getDocumentMetaData against a ProductionManager. */
  public void testConnectorNotFoundGetDocumentMetaDataProductionManager()
      throws Exception {
    Manager manager = getProductionManager();
    // UnknownConnector does not exist.
    try {
      GetDocumentContent.getDocumentMetaData(createMockRequest(),
          manager, "unknownConnector", docid);
      fail("Expected ConnectorNotFoundException, but got none.");
    } catch (ConnectorNotFoundException expected) {
      // Expected
    }
  }

  /** Test getDocumentMetaData against a ProductionManager. */
  public void testDocumentNotFoundGetDocumentMetaDataProductionManager()
      throws Exception {
    Manager manager = getProductionManager();
    // Unknown document does not exist.
    try {
      GetDocumentContent.getDocumentMetaData(createMockRequest(),
          manager, connectorName, MockRetriever.DOCID_NOT_FOUND);
      fail("Expected DocumentNotFoundException, but got none.");
    } catch (DocumentNotFoundException expected) {
      // Expected.
    }
  }

  /** Test getDocumentMetaData against a ProductionManager. */
  public void testDocumentNoAccessGetDocumentMetaDataProductionManager()
      throws Exception {
    Manager manager = getProductionManager();
    // Insufficient access to document.
    try {
      GetDocumentContent.getDocumentMetaData(createMockRequest(),
          manager, connectorName, MockRetriever.DOCID_NO_ACCESS);
      fail("Expected DocumentAccessException, but got none.");
    } catch (DocumentAccessException expected) {
      // Expected.
    }
  }

  /** Test getLastModified function against a ProductionManager. */
  public void testGetLastModifiedProductionManager() throws Exception {
    patchRealProductionManager();
    Manager manager = getProductionManager();
    long lastModified;

    // Connector regular docids have lastModified.
    Document metaData = GetDocumentContent.getDocumentMetaData(
        createMockRequest(), manager, connectorName, docid);
    assertNotNull(metaData);
    lastModified = GetDocumentContent.handleGetLastModified(metaData);
    assertEquals(3600 * 1000, lastModified);

    // This Document has no lastModifiedDate
    metaData = GetDocumentContent.getDocumentMetaData(createMockRequest(),
        manager, connectorName, MockRetriever.DOCID_NO_LASTMODIFIED);
    assertNotNull(metaData);
    lastModified = GetDocumentContent.handleGetLastModified(metaData);
    assertEquals(-1L, lastModified);
  }

  /** Test getContentType function against a ProductionManager. */
  public void testGetContentTypeProductionManager() throws Exception {
    patchRealProductionManager();
    Manager manager = getProductionManager();
    String contentType;

    // Connector regular docids have lastModified.
    Document metaData = GetDocumentContent.getDocumentMetaData(
        createMockRequest(), manager, connectorName, docid);
    assertNotNull(metaData);
    contentType = GetDocumentContent.handleGetContentType(metaData);
    assertEquals("text/plain", contentType);

    // This Document has no mime type.
    metaData = GetDocumentContent.getDocumentMetaData(createMockRequest(),
        manager, connectorName, MockRetriever.DOCID_NO_MIMETYPE);
    assertNotNull(metaData);
    contentType = GetDocumentContent.handleGetContentType(metaData);
    assertEquals(SpiConstants.DEFAULT_MIMETYPE, contentType);
  }

  /** Test ProductionManager getDocumentContent should deny SecMgr. */
  public void testGetDocumentContentFromSecMgr() throws Exception {
    patchRealProductionManager();
    MockHttpServletRequest req = createMockRequest(connectorName, docid);
    req.addHeader("User-Agent", "SecMgr");
    MockHttpServletResponse res = new MockHttpServletResponse();
    new GetDocumentContent().service(req, res);
    assertEquals(403, res.getStatus());
  }

  /** Test ProductionManager getDocumentContent should deny HEAD from SecMgr. */
  public void testGetDocumentContentHeadFromSecMgr() throws Exception {
    patchRealProductionManager();
    MockHttpServletRequest req = createMockRequest(connectorName, docid);
    req.setMethod("HEAD");
    req.addHeader("User-Agent", "SecMgr");
    MockHttpServletResponse res = new MockHttpServletResponse();
    new GetDocumentContent().service(req, res);
    assertEquals(403, res.getStatus());
  }

  /** Test ProductionManager getDocumentContent should deny Legacy Headrequest.
      Cookie based Authn will generate a GET with Range:0-0 */
  public void testGetDocumentContentCookieHeadRequest() throws Exception {
    patchRealProductionManager();
    MockHttpServletRequest req = createMockRequest(connectorName, docid);
    req.addHeader("User-Agent", "gsa-crawler");
    req.addHeader("Range", "0-0");
    MockHttpServletResponse res = new MockHttpServletResponse();
    new GetDocumentContent().service(req, res);
    assertEquals(403, res.getStatus());
  }

  /** Test ProductionManager getDocumentContent should deny
      Legacy Headrequest. */
  public void testGetDocumentContentHeadRequest() throws Exception {
    patchRealProductionManager();
    MockHttpServletRequest req = createMockRequest(connectorName, docid);
    req.setMethod("HEAD");
    req.addHeader("User-Agent", "gsa-crawler");
    MockHttpServletResponse res = new MockHttpServletResponse();
    new GetDocumentContent().service(req, res);
    assertEquals(403, res.getStatus());
  }

  /** Test requiring GSA to use authentication for private doc. */
  public void testHttpBasicWithoutCredentials() throws Exception {
    MockHttpServletRequest req = createMockRequest();
    // connector5's documents are private.
    Document metaData = GetDocumentContent.getDocumentMetaData(
        req, MockManager.getInstance(), "connector5", docid);
    MockHttpServletResponse res = new MockHttpServletResponse();
    int status =
        GetDocumentContent.handleMarkingDocumentSecurity(req, res, metaData);
    assertEquals(401, status);
    assertNotNull(res.getHeader("WWW-Authenticate"));
    assertEquals(null, res.getHeader("X-Gsa-Serve-Security"));
  }

  /** Test successful use of authentication for private doc. */
  public void testHttpBasicWithArbitraryCredentials() throws Exception {
    MockHttpServletRequest req = createMockRequest();
    // Credentials: gsa:password
    req.addHeader("Authorization", "Basic Z3NhOnBhc3N3b3Jk");
    // connector5's documents are private.
    Document metaData = GetDocumentContent.getDocumentMetaData(
        req, MockManager.getInstance(), "connector5", docid);
    MockHttpServletResponse res = new MockHttpServletResponse();
    int status =
        GetDocumentContent.handleMarkingDocumentSecurity(req, res, metaData);
    assertEquals(200, status);
    assertEquals(null, res.getHeader("X-Gsa-Serve-Security"));
  }

  public void testGsaServeSecurityHeader() throws Exception {
    MockHttpServletRequest req = createMockRequest();
    // connector5's documents are private.
    Document metaData = GetDocumentContent.getDocumentMetaData(
        req, MockManager.getInstance(), "connector5", docid);
    MockHttpServletResponse res = new MockHttpServletResponse();
    GetDocumentContent.setFeedConnection(new MockFeedConnection());
    try {
      int status =
          GetDocumentContent.handleMarkingDocumentSecurity(req, res, metaData);
      assertEquals(200, status);
      assertEquals("secure", res.getHeader("X-Gsa-Serve-Security"));
    } finally {
      GetDocumentContent.setFeedConnection(null);
    }
  }

  /**
   * Tests that the FeedConnection.supportsInheritedAcls answer is not
   * cached by GetDocumentContent.
   */
  public void testGsaServeSecurityHeaderWithFeedError() throws Exception {
    MockHttpServletRequest req = createMockRequest();
    // connector5's documents are private.
    Document metaData = GetDocumentContent.getDocumentMetaData(
        req, MockManager.getInstance(), "connector5", docid);
    MockHttpServletResponse res = new MockHttpServletResponse();
    GetDocumentContent.setFeedConnection(new MockFeedConnection() {
        @Override public boolean supportsInheritedAcls() { return false; } });
    try {
      int status =
          GetDocumentContent.handleMarkingDocumentSecurity(req, res, metaData);
      assertEquals(401, status);
      assertEquals(null, res.getHeader("X-Gsa-Serve-Security"));

      GetDocumentContent.setFeedConnection(new MockFeedConnection() {
          @Override public boolean supportsInheritedAcls() { return true; } });
      status =
          GetDocumentContent.handleMarkingDocumentSecurity(req, res, metaData);
      assertEquals(200, status);
      assertEquals("secure", res.getHeader("X-Gsa-Serve-Security"));
    } finally {
      GetDocumentContent.setFeedConnection(null);
    }
  }

  private void encodeQueryParameter(MockHttpServletRequest req)
      throws Exception {
    StringBuilder sb = new StringBuilder();
    @SuppressWarnings("unchecked")
    java.util.Enumeration<String> e = req.getParameterNames();
    for (String key : Collections.list(e)) {
      for (String value : req.getParameterValues(key)) {
        sb.append(URLEncoder.encode(key, "UTF-8"));
        sb.append("=");
        if (value != null) {
          sb.append(URLEncoder.encode(value, "UTF-8"));
        }
        sb.append("&");
      }
    }
    String query = sb.length() == 0 ? null : sb.substring(0, sb.length() - 1);
    req.setQueryString(query);
  }

  private MockHttpServletResponse getDocumentContent(
       MockHttpServletRequest req, String expectedContent) throws Exception {
    MockHttpServletResponse res = new MockHttpServletResponse();
    new GetDocumentContent().doGet(req, res);
    assertEquals(200, res.getStatus());
    assertEquals(expectedContent, res.getContentAsString());
    return res;
  }

  /**
   * Test method for the HttpServlet.doGet.
   */
  public void testDoGetNoIfModifiedSince() throws Exception {
    patchRealProductionManager();
    MockHttpServletRequest req = createMockRequest(connectorName, docid);
    MockHttpServletResponse res = getDocumentContent(req, docid);
    assertNull(res.getHeader("Content-Encoding"));
  }

  public void testSpecialCharsDocId() throws Exception {
    // connector6 checks docid values.
    MockHttpServletRequest req = createMockRequest(MockManager.CONNECTOR6,
        MockManager.CONNECTOR6_SPECIAL_CHAR_DOCID);
    MockHttpServletResponse res = new MockHttpServletResponse();
    GetDocumentContent.doGet(req, res, MockManager.getInstance());
    // connector6 checks docid values.
    assertEquals(200, res.getStatus());
    assertEquals(MockManager.CONNECTOR6_SUCCESS, res.getContentAsString());
  }

  /**
   * Test compression in the Servlet.
   */
  public void testDoGetWithCompression() throws Exception {
    patchRealProductionManager();
    String docid = "NowIsTheTimeForAllGoodMenToComeToTheAidOfTheCountry";
    MockHttpServletRequest req = createMockRequest(connectorName, docid);
    req.addHeader("Accept-Encoding", "gzip");
    GetDocumentContent.setUseCompression(true);
    MockHttpServletResponse res = new MockHttpServletResponse();
    new GetDocumentContent().doGet(req, res);
    assertEquals(200, res.getStatus());
    assertEquals("gzip", res.getHeader("Content-Encoding"));
    assertFalse(docid.equals(res.getContentAsString()));
    assertTrue(docid.equals(StringUtils.streamToString(
        new GZIPInputStream(new ByteArrayInputStream(
        res.getContentAsByteArray())))));
  }

  /**
   * Test If-Modified-Since, where lastModified returns -1.
   */
  public void testGetIfModifiedSinceNoLastModified() throws Exception {
    patchRealProductionManager();
    String docid = MockRetriever.DOCID_NO_LASTMODIFIED;
    MockHttpServletRequest req = createMockRequest(connectorName, docid);
    req.addHeader("If-Modified-Since", SystemClock.INSTANCE.getTimeMillis());
    getDocumentContent(req, docid);
  }

  /**
   * Test If-Modified-Since, where lastModified newer.
   */
  public void testGetIfModifiedSinceLastModified() throws Exception {
    patchRealProductionManager();
    MockHttpServletRequest req = createMockRequest(connectorName, docid);
    req.addHeader("If-Modified-Since", 1);
    getDocumentContent(req, docid);
  }

  /**
   * Test If-Modified-Since, where unmodified.
   */
  public void testGetUnModifiedSinceLastModified() throws Exception {
    patchRealProductionManager();
    MockHttpServletRequest req = createMockRequest(connectorName, docid);
    req.addHeader("If-Modified-Since", SystemClock.INSTANCE.getTimeMillis());
    MockHttpServletResponse res = new MockHttpServletResponse();
    new GetDocumentContent().service(req, res);
    assertEquals(304, res.getStatus());
    assertTrue(Strings.isNullOrEmpty(res.getContentAsString()));
  }

  /**
   * Test ContentType.
   */
  public void testDoGetContentType() throws Exception {
    patchRealProductionManager();
    MockHttpServletRequest req = createMockRequest(connectorName, docid);
    MockHttpServletResponse res = getDocumentContent(req, docid);
    assertTrue(res.getContentType().contains("text/plain"));
  }

  /**
   * Test ContentType, where document has no mime type property.
   */
  public void testDoGetContentTypeNoMimeType() throws Exception {
    patchRealProductionManager();
    String docid = MockRetriever.DOCID_NO_MIMETYPE;
    MockHttpServletRequest req = createMockRequest(connectorName, docid);
    MockHttpServletResponse res = getDocumentContent(req, docid);
    assertTrue(res.getContentType().contains(SpiConstants.DEFAULT_MIMETYPE));
  }

  /**
   * Test ContentLength.
   */
  public void testDoGetContentLength() throws Exception {
    patchRealProductionManager();
    MockHttpServletRequest req = createMockRequest(connectorName, docid);
    MockHttpServletResponse res = getDocumentContent(req, docid);
    assertEquals(docid.length(), res.getContentLength());
  }

  /**
   * Test ContentLength, where document has no contentLength property.
   */
  public void testDoGetNoContentLength() throws Exception {
    patchRealProductionManager();
    String docid = MockRetriever.DOCID_NO_CONTENT;
    MockHttpServletRequest req = createMockRequest(connectorName, docid);
    MockHttpServletResponse res = getDocumentContent(req, " ");
    assertEquals(0, res.getContentLength());
  }

  /**
   * Test ContentLength, where document has zero-length content.
   */
  public void testDoGetEmptyContent() throws Exception {
    patchRealProductionManager();
    String docid = MockRetriever.DOCID_EMPTY_CONTENT;
    MockHttpServletRequest req = createMockRequest(connectorName, docid);
    MockHttpServletResponse res = getDocumentContent(req, " ");
    assertEquals(0, res.getContentLength());
  }

  /**
   * Test document metadata is returned in a MetaData header.
   */
  public void testMetadataInHeader() throws Exception {
    patchRealProductionManager();
    MockHttpServletRequest req = createMockRequest(connectorName, docid);
    MockHttpServletResponse res = getDocumentContent(req, docid);
    String expected = "google%3Aaclinheritfrom"
        + "=http%3A%2F%2Fgoo%2F%3FConnectorName%3Dfoo%26docid%3Ddocid%26ExtrACL"
        + ",google%3Adisplayurl"
        + "=http%3A%2F%2Fwww.comtesturl.com%2Ftest%3Fdocid"
        + ",google%3Alastmodified=1969-12-31"
        + ",google%3Amimetype=text%2Fplain";
    String extMetadata = (String) res.getHeader("X-Gsa-External-Metadata");
    assertNotNull(extMetadata);
    assertEquals(extMetadata.replace(",", ", "), expected, extMetadata);
  }
}
