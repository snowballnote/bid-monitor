package com.comhu.bidmonitor.bid.api.dto;

public record BidApiErrorResponse(int status, String error, String code, String message) {
}
