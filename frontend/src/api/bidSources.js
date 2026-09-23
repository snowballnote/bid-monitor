const BASE_URL = '/api/bid-source-registrations';

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
    && typeof value.createdAt === 'string'
    && typeof value.updatedAt === 'string';
}

export async function getBidSourceRegistrations(signal) {
  const response = await fetch(BASE_URL, { signal, headers: { Accept: 'application/json' } });
  if (!response.ok) throw new BidSourceRegistrationRequestError(response.status);
  const result = await readJson(response);
  if (!Array.isArray(result) || !result.every(isRegistration)) {
    throw new BidSourceRegistrationRequestError(503);
  }
  return result;
}

export async function registerBidSource({ sourceName, siteUrl }, signal) {
  const response = await fetch(BASE_URL, {
    method: 'POST',
    signal,
    headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
    body: JSON.stringify({ sourceName, siteUrl }),
  });
  const result = await readJson(response);
  if (!response.ok) throw new BidSourceRegistrationRequestError(response.status);
  if (!isRegistration(result)) throw new BidSourceRegistrationRequestError(503);
  return result;
}
