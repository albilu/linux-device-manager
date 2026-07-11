package org.ldm.core.action;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

class PolkitPolicyTest {

    @Test
    void policyDeclaresActionAndHelperExecPath() throws Exception {
        Path policy = HelperFiles.polkitPolicy();
        assertTrue(Files.exists(policy), "policy file should exist at " + policy);

        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        // The polkit DTD is an external URL; do not fetch it during the test.
        f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        Document doc = f.newDocumentBuilder().parse(policy.toFile());

        Element action = (Element) doc.getElementsByTagName("action").item(0);
        assertEquals("org.ldm.manage-device", action.getAttribute("id"));

        String xml = Files.readString(policy);
        assertTrue(xml.contains("/usr/libexec/ldm-helper"), "must reference the helper exec path");
        assertTrue(xml.contains("org.freedesktop.policykit.exec.path"), "must set exec.path annotate");
        assertTrue(xml.contains("auth_admin"), "active action should require admin auth");
    }
}
