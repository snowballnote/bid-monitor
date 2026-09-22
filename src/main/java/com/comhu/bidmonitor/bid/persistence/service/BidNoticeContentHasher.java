package com.comhu.bidmonitor.bid.persistence.service;

import com.comhu.bidmonitor.bid.persistence.BidNotice;
import com.comhu.bidmonitor.dto.BidAttachmentDto;
import com.comhu.bidmonitor.dto.BidQualificationDto;
import com.comhu.bidmonitor.dto.LicenseRequirement;
import com.comhu.bidmonitor.dto.LicenseRequirementGroup;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/** Produces a stable hash from source content, excluding collection and UI-only values. */
@Component
public class BidNoticeContentHasher {

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    public String hash(BidNotice notice, BidQualificationDto source) {
        MessageDigest digest = sha256();
        add(digest, "noticeNumber", notice.getNoticeNumber());
        add(digest, "title", notice.getTitle());
        add(digest, "orderingOrganization", notice.getOrderingOrganization());
        add(digest, "publishedAt", dateTime(notice.getPublishedAt()));
        add(digest, "submissionDeadlineAt", dateTime(notice.getSubmissionDeadlineAt()));
        add(digest, "bidOpeningAt", dateTime(notice.getBidOpeningAt()));
        add(digest, "contractMethod", notice.getContractMethod());
        add(digest, "bidMethod", notice.getBidMethod());
        add(digest, "noticeStatus", notice.getNoticeStatus());
        add(digest, "noticeStatusCode", notice.getNoticeStatusCode());
        add(digest, "detailUrl", notice.getDetailUrl());
        add(digest, "budgetAmount", source.getAsignBdgtAmt());
        add(digest, "licenseLimit", source.getLicenseLimit());
        add(digest, "participationRegion", source.getParticipationRegion());
        add(digest, "awardMethodName", source.getSucsfbidMthdNm());
        add(digest, "awardMethodCode", source.getSucsfbidMthdCd());
        add(digest, "awardMethodStandard", source.getSucsfbidMthdAppStd());
        add(digest, "performanceCompetition", source.getArsltCmptYn());
        add(digest, "pqEvaluation", source.getPqEvalYn());
        add(digest, "tpEvaluation", source.getTpEvalYn());
        add(digest, "jointContractReceiptMethod", source.getCmmnSpldmdAgrmntRcptdocMethd());

        List<String> licenses = canonicalLicenses(source.getLicenseGroups());
        for (int index = 0; index < licenses.size(); index++) {
            add(digest, "license[" + index + "]", licenses.get(index));
        }
        List<String> attachments = canonicalAttachments(source.getAttachments());
        for (int index = 0; index < attachments.size(); index++) {
            add(digest, "attachment[" + index + "]", attachments.get(index));
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private List<String> canonicalLicenses(List<LicenseRequirementGroup> groups) {
        List<String> values = new ArrayList<>();
        if (groups == null) {
            return values;
        }
        for (LicenseRequirementGroup group : groups) {
            if (group == null) {
                continue;
            }
            List<LicenseRequirement> requirements = group.getRequirements();
            if (requirements == null || requirements.isEmpty()) {
                values.add(join(group.getGroupNo(), "", "", "", ""));
                continue;
            }
            for (LicenseRequirement requirement : requirements) {
                if (requirement != null) {
                    values.add(join(
                            group.getGroupNo(), requirement.getSequence(), requirement.getLicenseCode(),
                            requirement.getLicenseName(), requirement.getRawLicenseLimitName()
                    ));
                }
            }
        }
        values.sort(Comparator.naturalOrder());
        return values;
    }

    private List<String> canonicalAttachments(List<BidAttachmentDto> attachments) {
        if (attachments == null) {
            return List.of();
        }
        return attachments.stream()
                .filter(java.util.Objects::nonNull)
                .map(attachment -> join(
                        attachment.getFileName(), attachment.getFileUrl(), attachment.getDocumentType()
                ))
                .sorted()
                .toList();
    }

    private String join(String... values) {
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            String normalized = normalize(value);
            result.append(normalized.length()).append(':').append(normalized).append('|');
        }
        return result.toString();
    }

    private void add(MessageDigest digest, String field, String value) {
        String normalized = normalize(value);
        String encoded = field.length() + ":" + field + "=" + normalized.length() + ":" + normalized + "\n";
        digest.update(encoded.getBytes(StandardCharsets.UTF_8));
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private String dateTime(LocalDateTime value) {
        return value == null ? "" : DATE_TIME.format(value);
    }

    private MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available.", exception);
        }
    }
}
