const BASE_URL = '/api/bid-source-registrations';
const CATALOG_URL = '/api/bid-sources/catalog';

export class BidSourceRegistrationRequestError extends Error {
  constructor(status) {
    super(`수집처 등록 요청 실패: ${status}`);
    this.name = 'BidSourceRegistrationRequestError';
    this.status = status;
  }
}

async function readJson(response) {
  try {
    return await response.json();
  } catch {
    return null;
  }
}

function isRegistration(value) {
  return value && Number.isInteger(value.sourceId)
    && typeof value.sourceName === 'string'
    && typeof value.siteUrl === 'string'
    && typeof value.registrationStatus === 'string'
    && typeof value.collectionMethod === 'string'
    && typeof value.executionEnabled === 'boolean'
    && ['SUPPORTED', 'NEW_SITE'].includes(value.siteIdentification)
    && isNullableString(value.matchedSourceCode)
    && typeof value.registrationBindingSupported === 'boolean'
    && typeof value.createdAt === 'string'
    && typeof value.updatedAt === 'string';
}

function isNullableString(value) {
  return value == null || typeof value === 'string';
}

function isCatalogItem(value) {
  return value && (value.sourceId == null || Number.isInteger(value.sourceId))
    && isNullableString(value.sourceCode)
    && typeof value.sourceName === 'string'
    && typeof value.siteUrl === 'string'
    && ['FIXED', 'DISCOVERED'].includes(value.sourceType)
    && isNullableString(value.collectionMethod)
    && typeof value.executionEnabled === 'boolean'
    && isNullableString(value.registrationStatus)
    && isNullableString(value.checkStatus)
    && isNullableString(value.discoveryStatus)
    && isNullableString(value.reviewStatus)
    && isNullableString(value.lastSuccessAt)
    && isNullableString(value.lastFailureAt)
    && isNullableString(value.safeFailureCode);
}

function isDiscovery(value) {
  return value && Number.isInteger(value.sourceId)
    && typeof value.discoveryStatus === 'string'
    && isNullableString(value.detectedCollectionMethod)
    && isNullableString(value.listPageUrl)
    && isNullableString(value.detailUrlPattern)
    && isNullableString(value.identifierMapping)
    && isNullableString(value.titleMapping)
    && isNullableString(value.agencyMapping)
    && isNullableString(value.publishedDateMapping)
    && isNullableString(value.deadlineMapping)
    && isNullableString(value.statusMapping)
    && isNullableString(value.attachmentMapping)
    && isNullableString(value.paginationMapping)
    && isNullableString(value.identifierConfidence)
    && isNullableString(value.titleConfidence)
    && isNullableString(value.deadlineConfidence)
    && typeof value.attachmentDetected === 'boolean'
    && typeof value.paginationDetected === 'boolean'
    && Array.isArray(value.reasonCodes)
    && value.reasonCodes.every(reason => typeof reason === 'string')
    && isNullableString(value.analyzedAt);
}

async function requestJson(url, options, validator) {
  const response = await fetch(url, options);
  const result = await readJson(response);
  if (!response.ok) throw new BidSourceRegistrationRequestError(response.status);
  if (!validator(result)) throw new BidSourceRegistrationRequestError(503);
  return result;
}

export async function getBidSourceCatalog(signal) {
  const response = await fetch(CATALOG_URL, { signal, headers: { Accept: 'application/json' } });
  if (!response.ok) throw new BidSourceRegistrationRequestError(response.status);
  const result = await readJson(response);
  if (!Array.isArray(result) || !result.every(isCatalogItem)) {
    throw new BidSourceRegistrationRequestError(503);
  }
  return result;
}

export async function registerBidSource({ sourceName, siteUrl }, signal) {
  return requestJson(BASE_URL, {
    method: 'POST',
    signal,
    headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
    body: JSON.stringify({ sourceName, siteUrl }),
  }, isRegistration);
}

export async function updateBidSource(sourceId, { sourceName, siteUrl }, signal) {
  return requestJson(`${BASE_URL}/${sourceId}`, {
    method: 'PATCH',
    signal,
    headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
    body: JSON.stringify({ sourceName, siteUrl }),
  }, isRegistration);
}

export async function deleteBidSource(sourceId, signal) {
  const response = await fetch(`${BASE_URL}/${sourceId}`, {
    method: 'DELETE',
    signal,
    headers: { Accept: 'application/json' },
  });
  if (!response.ok) throw new BidSourceRegistrationRequestError(response.status);
}

export async function getBidSourceRegistrations(signal) {
  return requestJson(BASE_URL, {
    signal,
    headers: { Accept: 'application/json' },
  }, value => Array.isArray(value) && value.every(isRegistration));
}

export async function checkBidSource(sourceId, signal) {
  return requestJson(`${BASE_URL}/${sourceId}/check`, {
    method: 'POST',
    signal,
    headers: { Accept: 'application/json' },
  }, isRegistration);
}

export async function discoverBidSource(sourceId, signal) {
  return requestJson(`${BASE_URL}/${sourceId}/discovery`, {
    method: 'POST',
    signal,
    headers: { Accept: 'application/json' },
  }, isDiscovery);
}

export async function getBidSourceDiscovery(sourceId, signal) {
  return requestJson(`${BASE_URL}/${sourceId}/discovery`, {
    signal,
    headers: { Accept: 'application/json' },
  }, isDiscovery);
}

export async function reviewBidSource(sourceId, registrationStatus, collectionMethod, signal) {
  return requestJson(`${BASE_URL}/${sourceId}/review`, {
    method: 'PATCH',
    signal,
    headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
    body: JSON.stringify({ registrationStatus, collectionMethod }),
  }, isRegistration);
}

export async function bindBidSource(sourceId, sourceCode, signal) {
  return requestJson(`${BASE_URL}/${sourceId}/binding`, {
    method: 'PATCH',
    signal,
    headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
    body: JSON.stringify({ sourceCode }),
  }, isRegistration);
}

export async function activateBidSource(sourceId, executionEnabled, signal) {
  return requestJson(`${BASE_URL}/${sourceId}/activation`, {
    method: 'PATCH',
    signal,
    headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
    body: JSON.stringify({ executionEnabled }),
  }, isRegistration);
}
