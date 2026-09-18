import { useRef, useState } from 'react';
import { validatePerformanceEntry, validatePerformanceEntryMetadata } from '../../api/submissions/performanceDocuments';
import Button from '../Button';

const fields = [
  ['pptNumber', 'PPT 번호', 100], ['businessName', '사업명', 1000], ['businessPeriod', '사업기간', 500],
  ['contractAmount', '계약금액', 200], ['client', '발주처', 500],
];

export default function PerformanceEntryDialog({ entry, onSave, onClose }) {
  const dialog = useRef(null);
  const [values, setValues] = useState(() => ({
    ...Object.fromEntries(fields.map(([name]) => [name, entry?.info?.[name] || ''])),
    businessStatus: entry?.info?.businessStatus || '', kitcStatus: entry?.info?.kitcStatus || 'NEEDED',
    requestedAt: entry?.info?.requestedAt || '', repliedAt: entry?.info?.repliedAt || '',
  }));
  const [errors, setErrors] = useState({});
  const [saving, setSaving] = useState(false);
  const savingRef = useRef(false);
  function setDialog(node) { dialog.current = node; if (node && !node.open) node.showModal(); }
  function close() { if (!savingRef.current) { dialog.current.close(); onClose(); } }
  async function submit(event) {
    event.preventDefault();
    if (savingRef.current) return;
    const validation = { ...validatePerformanceEntry(values), ...(entry ? validatePerformanceEntryMetadata(values) : {}) };
    setErrors(validation);
    if (Object.keys(validation).length) return;
    savingRef.current = true; setSaving(true);
    try { await onSave(values); dialog.current.close(); onClose(); }
    catch (error) { setErrors({ [error.field || '_form']: error.message }); }
    finally { savingRef.current = false; setSaving(false); }
  }
  function change(name, value) {
    setValues(current => {
      if (name !== 'kitcStatus') return { ...current, [name]: value };
      return { ...current, kitcStatus: value,
        requestedAt: value === 'NEEDED' ? '' : current.requestedAt,
        repliedAt: value === 'RECEIVED' ? current.repliedAt : '' };
    });
    setErrors(current => ({ ...current, [name]: undefined, requestedAt: undefined, repliedAt: undefined, _form: undefined }));
  }
  return <dialog ref={setDialog} className="common-document-dialog performance-entry-dialog"
    aria-labelledby="performance-entry-title" onCancel={event => { event.preventDefault(); close(); }}>
    <header><h2 id="performance-entry-title">{entry ? '실적 수정' : '실적 추가'}</h2></header>
    <form noValidate onSubmit={submit}>
      {fields.map(([name, label, max]) => <label key={name}>{label}
        <input name={name} value={values[name]} maxLength={max} disabled={saving}
          aria-invalid={Boolean(errors[name])} aria-describedby={errors[name] ? `performance-${name}-error` : undefined}
          onChange={event => change(name, event.target.value)} />
        {errors[name] && <small id={`performance-${name}-error`} className="field-error">{errors[name]}</small>}
      </label>)}
      <small className="performance-period-help">사업기간 예: 2024.01 ~ 2025.12 또는 2024.01 ~ 수행중</small>
      {entry && <fieldset className="performance-entry-metadata"><legend>사업상태·KITC</legend>
        <label>사업상태
          <select name="businessStatus" value={values.businessStatus} disabled={saving} onChange={event => change('businessStatus', event.target.value)}>
            <option value="">자동 판정</option>
            <option value="COMPLETED">수행완료 · 수동</option><option value="IN_PROGRESS">수행중 · 수동</option>
          </select>{errors.businessStatus && <small className="field-error">{errors.businessStatus}</small>}
        </label>
        <label>KITC 상태
          <select name="kitcStatus" value={values.kitcStatus} disabled={saving} onChange={event => change('kitcStatus', event.target.value)}>
            <option value="NEEDED">요청 필요</option><option value="REQUESTED">요청함</option><option value="RECEIVED">회신 완료</option>
          </select>{errors.kitcStatus && <small className="field-error">{errors.kitcStatus}</small>}
        </label>
        <label>요청일<input name="requestedAt" type="date" value={values.requestedAt} disabled={saving || values.kitcStatus === 'NEEDED'}
          onChange={event => change('requestedAt', event.target.value)} /></label>
        <label>회신일<input name="repliedAt" type="date" value={values.repliedAt} disabled={saving || values.kitcStatus !== 'RECEIVED'}
          onChange={event => change('repliedAt', event.target.value)} /></label>
        <small>파일 연결과 증빙유형은 변경하지 않습니다.</small>
      </fieldset>}
      {errors._form && <p role="alert">{errors._form}</p>}
      {saving && <p role="status">실적 저장 중…</p>}
      <footer><Button className="ui-button ui-button-secondary" disabled={saving} onClick={close}>취소</Button>
        <Button className="ui-button ui-button-primary" type="submit" disabled={saving}>저장</Button></footer>
    </form>
  </dialog>;
}
