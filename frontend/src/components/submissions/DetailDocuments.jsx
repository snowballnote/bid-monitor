import { groups } from '../../pages/submissions/detailView';
import { legacyDetailUrl } from '../../api/submissions';
import { downloadUrl } from '../../api/submissions/detail';
import Button from '../Button';

export default function DetailDocuments({ project, view }) {
  return <div className="submission-columns">
    <section className="document-picker surface-card" aria-labelledby="needed-documents-title">
      <header className="document-picker-heading"><h2 id="needed-documents-title">필요한 서류</h2></header>
      <p className="detail-help">회사 공통서류는 위에서 선택할 수 있습니다. 나머지 서류와 파일 변경은 상세 관리에서 진행하세요.</p>
      {!view.total && <p className="panel-state">선택된 제출서류가 없습니다.</p>}
      <div className="detail-required-groups">{Object.entries(groups).map(([group, label]) => <section key={group}>
        <h3>{label}</h3><ul>
          {view.rows.filter(row => row.group === group).map(row => <li key={row.id}>{row.documentName}</li>)}
          {group === 'PERSONNEL' && view.personnel.map((doc, index) => <li key={`person-${index}`}>{doc.personName} · {doc.label || doc.type}</li>)}
        </ul>{!view.summary.find(row => row.group === group)?.total && <p>선택된 서류 없음</p>}
      </section>)}</div>
      <div className="detail-actions"><Button href={legacyDetailUrl(project.id, 'document-picker')} className="ui-button ui-button-secondary">필요 서류 관리</Button></div>
    </section>
    <section className="requirement-panel surface-card" aria-labelledby="prepared-documents-title">
      <header className="panel-header"><h2 id="prepared-documents-title">선택/준비된 서류</h2>
        {view.downloadable ? <Button href={downloadUrl(project.id)} className="ui-button ui-button-secondary">전체 ZIP 다운로드</Button>
          : <Button className="ui-button ui-button-secondary" disabled>전체 ZIP 다운로드</Button>}</header>
      {!view.downloadable && <p className="detail-help">다운로드 가능한 파일이 없습니다.</p>}
      {!view.total ? <p className="panel-state">준비할 서류가 없습니다.</p> : <div className="requirement-table-scroll" tabIndex={0} aria-label="선택 서류 준비 현황 표">
        <table className="submission-project-table requirement-table"><thead><tr>{['서류명', '구분', '상태', '연결 파일', '작업'].map(label => <th key={label} scope="col">{label}</th>)}</tr></thead>
          <tbody>{view.rows.map(row => <tr key={row.id}><th scope="row">{row.documentName}</th><td>{groups[row.group]}</td>
            <td><span className={`requirement-state${row.ready ? ' complete' : ' attention'}`}>{row.status}</span></td><td>{row.file?.originalFilename || '—'}</td>
            <td><Button href={row.group === 'COMPANY_COMMON' ? '#/documents' : row.group === 'OTHER' ? '#other-documents' : legacyDetailUrl(project.id, 'requirement-title')} className="ui-button ui-button-secondary">상세 관리</Button></td></tr>)}
            {view.personnel.map((doc, index) => <tr key={`person-${index}`}><th scope="row">{doc.personName} · {doc.label || doc.type}</th><td>인력·자격</td>
              <td><span className={`requirement-state${doc.filename ? ' complete' : ' attention'}`}>{doc.filename ? '준비 완료' : '파일 미등록'}</span></td><td>{doc.filename || '—'}</td>
              <td><Button href={legacyDetailUrl(project.id, 'personnel-panel')} className="ui-button ui-button-secondary">상세 관리</Button></td></tr>)}</tbody>
        </table></div>}
    </section>
  </div>;
}
