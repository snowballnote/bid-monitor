import { useRef, useState } from 'react';
import { validatePerformanceEntry } from '../../api/submissions/performanceDocuments';
import Button from '../Button';

const fields = [
  ['pptNumber', 'PPT 번호', 100], ['businessName', '사업명', 1000], ['businessPeriod', '사업기간', 500],
  ['contractAmount', '계약금액', 200], ['client', '발주처', 500],
];

export default function PerformanceEntryDialog({ entry, onSave, onClose }) {
  const dialog = useRef(null);
  const [values, setValues] = useState(() => Object.fromEntries(fields.map(([name]) => [name, entry?.info?.[name] || ''])));
  const [errors, setErrors] = useState({});
  const [saving, setSaving] = useState(false);
  const savingRef = useRef(false);
  function setDialog(node) { dialog.current = node; if (node && !node.open) node.showModal(); }
  function close() { if (!savingRef.current) { dialog.current.close(); onClose(); } }
  async function submit(event) {
    event.preventDefault();
    if (savingRef.current) return;
    const validation = validatePerformanceEntry(values);
    setErrors(validation);
    if (Object.keys(validation).length) return;
    savingRef.current = true; setSaving(true);
    try { await onSave(values); dialog.current.close(); onClose(); }
    catch (error) { setErrors({ [error.field || '_form']: error.message }); }
    finally { savingRef.current = false; setSaving(false); }
  }
  return <dialog ref={setDialog} className="common-document-dialog performance-entry-dialog"
    aria-labelledby="performance-entry-title" onCancel={event => { event.preventDefault(); close(); }}>
    <header><h2 id="performance-entry-title">{entry ? '실적 수정' : '실적 추가'}</h2></header>
    <form noValidate onSubmit={submit}>
      {fields.map(([name, label, max]) => <label key={name}>{label}
        <input name={name} value={values[name]} maxLength={max} disabled={saving}
          aria-invalid={Boolean(errors[name])} aria-describedby={errors[name] ? `performance-${name}-error` : undefined}
          onChange={event => { setValues(current => ({ ...current, [name]: event.target.value })); setErrors(current => ({ ...current, [name]: undefined, _form: undefined })); }} />
        {errors[name] && <small id={`performance-${name}-error`} className="field-error">{errors[name]}</small>}
      </label>)}
      <small className="performance-period-help">사업기간 예: 2024.01 ~ 2025.12 또는 2024.01 ~ 수행중</small>
      {entry && <small>파일 연결, 증빙유형, KITC 상태와 요청·회신일은 그대로 유지됩니다.</small>}
      {errors._form && <p role="alert">{errors._form}</p>}
      {saving && <p role="status">실적 저장 중…</p>}
      <footer><Button className="ui-button ui-button-secondary" disabled={saving} onClick={close}>취소</Button>
        <Button className="ui-button ui-button-primary" type="submit" disabled={saving}>저장</Button></footer>
    </form>
  </dialog>;
}
