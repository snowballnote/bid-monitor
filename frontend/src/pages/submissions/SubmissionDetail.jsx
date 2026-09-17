import { useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { getDetail } from '../../api/submissions/detail';
import { detailView } from './detailView';
import DetailSummary from '../../components/submissions/DetailSummary';
import DetailDocuments from '../../components/submissions/DetailDocuments';
import CommonDocuments from '../../components/submissions/CommonDocuments';
import Button from '../../components/Button';
import Personnel from '../../components/submissions/Personnel';
import OtherDocuments from '../../components/submissions/OtherDocuments';
import PerformanceDocuments from '../../components/submissions/PerformanceDocuments';
import styles from '../../../../src/main/resources/static/submissions/submissions.css?inline';
import './submissions.css';

export default function SubmissionDetail() {
  const { caseId } = useParams();
  const [state, setState] = useState({ id: null, status: 'loading' });
  const [revision, setRevision] = useState(0);
  const [savingCommon, setSavingCommon] = useState(false);
  const [savingPersonnel, setSavingPersonnel] = useState(false);
  const [savingOther, setSavingOther] = useState(false);
  useEffect(() => {
    const controller = new AbortController();
    setSavingCommon(false);
    setSavingPersonnel(false);
    setSavingOther(false);
    setState({ id: caseId, status: 'loading' });
    getDetail(caseId, controller.signal).then(data => {
      if (!controller.signal.aborted) setState({ id: caseId, status: 'success', data, view: detailView(data) });
    }).catch(error => {
      if (!controller.signal.aborted) setState({ id: caseId, status: 'error', message: error.message });
    });
    return () => controller.abort();
  }, [caseId, revision]);
  const current = state.id === caseId ? state : { status: 'loading' };
  return <main className="react-submissions react-submission-detail"><style>{styles}</style>
    <header className="submission-topbar" aria-label="현재 위치"><span>Biz Assist</span><span>/</span><Link to="/submissions">서류 모으기</Link><span>/</span><strong>프로젝트 상세</strong></header>
    <div className="page-container submission-page">
      <header className="page-heading"><h1>프로젝트 상세</h1></header>
      <div className="project-controls detail-actions"><Link className="ui-button ui-button-secondary" to="/submissions">프로젝트 목록</Link>
        <Button className="ui-button ui-button-secondary" disabled={current.status === 'loading' || savingCommon || savingPersonnel || savingOther} onClick={() => setRevision(value => value + 1)}>새로고침</Button></div>
      {current.status === 'loading' && <p className="workspace-state" role="status">제출서류 작업을 불러오는 중입니다.</p>}
      {current.status === 'error' && <p className="workspace-state error" role="alert">{current.message}</p>}
      {current.status === 'success' && <div id="submission-workspace" className="submission-workspace">
        <DetailSummary project={current.data.project} view={current.view} />
        <CommonDocuments key={caseId} data={current.data} view={current.view} onBusy={setSavingCommon} onSaved={result => setState(previous => {
          if (previous.id !== caseId || previous.status !== 'success') return previous;
          const data = { ...previous.data, ...result };
          return { ...previous, data, view: detailView(data) };
        })} />
        <Personnel key={caseId} projectId={caseId} people={current.data.people} onBusy={setSavingPersonnel} onSaved={people => setState(previous => {
          if (previous.id !== caseId || previous.status !== 'success') return previous;
          const data = { ...previous.data, people };
          return { ...previous, data, view: detailView(data) };
        })} />
        <PerformanceDocuments key={`performance-${caseId}`} project={current.data.project}
          required={current.data.requirements.some(row => row.performanceSelectionRequired)} onLoaded={entries => setState(previous => {
            if (previous.id !== caseId || previous.status !== 'success') return previous;
            const data = { ...previous.data, entries };
            return { ...previous, data, view: detailView(data) };
          })} />
        <OtherDocuments key={`other-${caseId}`} data={current.data} view={current.view} onBusy={setSavingOther} onSaved={result => setState(previous => {
          if (previous.id !== caseId || previous.status !== 'success') return previous;
          const data = { ...previous.data, ...result };
          return { ...previous, data, view: detailView(data) };
        })} />
        <DetailDocuments project={current.data.project} view={current.view} />
      </div>}
    </div>
  </main>;
}
