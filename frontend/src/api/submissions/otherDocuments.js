import { fetchList } from '../client';
import { collectCommonDocuments, replaceSubmissionSelections } from './index';
import { getSavedDocuments } from './commonDocuments';

export async function getOtherCandidates(caseId, requirementId, signal) {
  const rows = await fetchList(`/api/submission-cases/${encodeURIComponent(caseId)}/requirements/${encodeURIComponent(requirementId)}/candidates`, signal);
  if (rows.some(row => !row || row.fileId == null || typeof row.originalFilename !== 'string')) {
    throw new Error('후보 파일 응답을 확인할 수 없습니다.');
  }
  return rows;
}

export const saveOtherRequirements = (caseId, requirements) => collectCommonDocuments(caseId, requirements);
export const saveOtherSelections = (caseId, selections) => replaceSubmissionSelections(caseId, selections);
export const getOtherSavedState = getSavedDocuments;
