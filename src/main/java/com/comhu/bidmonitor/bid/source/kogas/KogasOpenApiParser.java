
package com.comhu.bidmonitor.bid.source.kogas;

import org.springframework.stereotype.Component;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

@Component
public class KogasOpenApiParser {

    public KogasApiPage parse(String xml) {
        if (xml == null || xml.isBlank() || xml.length() > 2_000_000) {
            throw new IllegalArgumentException("Invalid KOGAS XML response");
        }

        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();

            // 외부 엔터티와 DTD를 차단해 XXE 공격을 방지한다.
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature(
                    "http://apache.org/xml/features/disallow-doctype-decl", true
            );
            factory.setFeature(
                    "http://xml.org/sax/features/external-general-entities", false
            );
            factory.setFeature(
                    "http://xml.org/sax/features/external-parameter-entities", false
            );
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);

            Element root = factory.newDocumentBuilder()
                    .parse(new InputSource(new StringReader(xml)))
                    .getDocumentElement();

            if (root == null || !"response".equals(root.getTagName())) {
                throw new IllegalStateException("Invalid KOGAS response root");
            }

            Element header = requiredChild(root, "header");
            String resultCode = value(header, "resultCode");

            if (!"00".equals(resultCode)) {
                throw new IllegalStateException("KOGAS API returned an error");
            }

            Element body = requiredChild(root, "body");

            int totalCount = Integer.parseInt(value(body, "totalCount"));
            int pageNo = Integer.parseInt(value(body, "pageNo"));
            int numOfRows = Integer.parseInt(value(body, "numOfRows"));

            if (totalCount < 0 || pageNo < 1 || numOfRows < 1) {
                throw new IllegalStateException("Invalid pagination data");
            }

            List<KogasApiNotice> notices = new ArrayList<>();
            Element items = child(body, "items");

            if (items != null) {
                for (Node node = items.getFirstChild();
                     node != null;
                     node = node.getNextSibling()) {

                    if (!(node instanceof Element item)
                            || !"item".equals(item.getTagName())) {
                        continue;
                    }

                    String noticeCode = value(item, "NOTICE_CODE");

                    if (noticeCode.isBlank()) {
                        throw new IllegalStateException(
                                "KOGAS notice code is missing"
                        );
                    }

                    notices.add(new KogasApiNotice(
                            noticeCode,
                            value(item, "NOTICE_NAME"),
                            value(item, "BID_TYPE_NAME"),
                            value(item, "WORK_TYPE_NAME"),
                            value(item, "CONT_METHOD_NAME"),
                            value(item, "NOTICE_DT"),
                            value(item, "END_DT"),
                            value(item, "OPEN_DT"),
                            value(item, "CANCEL_YN")
                    ));
                }
            }

            return new KogasApiPage(
                    totalCount,
                    pageNo,
                    numOfRows,
                    List.copyOf(notices)
            );

        } catch (ParserConfigurationException | SAXException | IOException e) {
            throw new IllegalStateException("KOGAS XML parsing failed", e);
        }
    }

    private Element requiredChild(Element parent, String name) {
        Element element = child(parent, name);

        if (element == null) {
            throw new IllegalStateException(
                    "Missing KOGAS XML element: " + name
            );
        }

        return element;
    }

    private Element child(Element parent, String name) {
        for (Node node = parent.getFirstChild();
             node != null;
             node = node.getNextSibling()) {

            if (node instanceof Element element
                    && name.equals(element.getTagName())) {
                return element;
            }
        }

        return null;
    }

    private String value(Element parent, String name) {
        Element element = child(parent, name);
        return element == null ? "" : element.getTextContent().trim();
    }

    public record KogasApiPage(
            int totalCount,
            int pageNo,
            int numOfRows,
            List<KogasApiNotice> notices
    ) {}

    public record KogasApiNotice(
            String noticeCode,
            String noticeName,
            String bidTypeName,
            String workTypeName,
            String contractMethodName,
            String noticeDate,
            String endDate,
            String openDate,
            String cancelYn
    ) {}
}