import { getCaseResource } from './index';

export async function getSavedDocuments(id) {
  const [requirements, packageData] = await Promise.all([
    getCaseResource(id, '/requirements'), getCaseResource(id, '/package'),
  ]);
  if (!Array.isArray(requirements) || requirements.some(row => !row || row.id == null)
      || !Array.isArray(packageData?.selections) || packageData.selections.some(row => !row || row.requirementId == null)) {
    throw new Error('저장된 서류 상태를 확인할 수 없습니다.');
  }
  return { requirements, selections: packageData.selections };
}
