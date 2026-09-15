import { getCaseResource } from './index';
import { fetchList } from '../client';

export async function getDetail(caseId, signal) {
  if (!/^[1-9]\d*$/.test(caseId)) throw new Error('프로젝트 번호를 확인해 주세요.');
  const [project, requirements, packageData, people, masters] = await Promise.all([
    getCaseResource(caseId, '', signal), getCaseResource(caseId, '/requirements', signal),
    getCaseResource(caseId, '/package', signal), getCaseResource(caseId, '/people', signal),
    fetchList('/api/submission-document-masters', signal),
  ]);
  if (!project || String(project.id) !== caseId || !Array.isArray(requirements)
      || requirements.some(row => !row || row.id == null) || !Array.isArray(packageData?.selections)
      || packageData.selections.some(row => !row || row.requirementId == null)
      || !Array.isArray(people) || people.some(person => !Array.isArray(person?.documents))) {
    throw new Error('프로젝트 상세 응답을 확인할 수 없습니다.');
  }
  const entries = project.performanceProjectId && requirements.some(row => row.performanceSelectionRequired)
    ? await fetchList(`/api/performance-projects/${encodeURIComponent(project.performanceProjectId)}/entries`, signal) : [];
  return { project, requirements, selections: packageData.selections, people, masters, entries };
}

export const downloadUrl = caseId => `/api/submission-cases/${encodeURIComponent(caseId)}/download`;
