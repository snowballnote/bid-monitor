import { useMemo, useRef, useState } from 'react';
import { createPerformanceEntry, importPerformanceEntries } from '../../api/submissions/performanceDocuments';
import Button from '../Button';

const labels = ['PPT 번호', '사업명', '사업기간', '계약금액', '발주처'];
const names = ['pptNumber', 'businessName', 'businessPeriod', 'contractAmount', 'client'];

function htmlRows(html) {
  const table = new DOMParser().parseFromString(html, 'text/html').querySelector('table');
  if (!table) return [];
  return [...table.rows].map(row => ({
    cells: [...row.cells].map(cell => {
      const content = cell.cloneNode(true);
      content.querySelectorAll('script, style').forEach(node => node.remove());
      content.querySelectorAll('br').forEach(node => node.replaceWith('\n'));
      content.querySelectorAll('p, div').forEach(node => node.append('\n'));
      return content.textContent.replace(/\u00a0/g, ' ').trim();
    }),
    merged: [...row.cells].some(cell => cell.colSpan > 1 || cell.rowSpan > 1),
  }));
}

function textRows(source) {
  const rows = []; let cells = []; let value = ''; let quoted = false;
  const input = source.replace(/\r\n?/g, '\n');
  for (let index = 0; index < input.length; index++) {
    const character = input[index];
    if (character === '"' && (quoted || !value)) {
      if (quoted && input[index + 1] === '"') { value += '"'; index++; } else quoted = !quoted;
    } else if (!quoted && (character === '\t' || character === '\n')) {
      cells.push(value); value = '';
      if (character === '\n') { rows.push({ cells }); cells = []; }
    } else value += character;
  }
  cells.push(value); rows.push({ cells, malformed: quoted }); return rows;
}

function tableText(rows) {
  return rows.map(row => row.cells.map(value => /[\t\n"]/.test(value)
    ? `"${value.replaceAll('"', '""')}"` : value).join('\t')).join('\n');
}

function previewRows(text, html) {
  const rows = html ? htmlRows(html) : textRows(text);
  return rows.map((row, index) => {
    const cells = row.cells.map(value => value.trim());
    const compact = cells.map(value => value.replace(/\s/g, ''));
    const header = ['번호', '순번', 'No', 'No.'].includes(compact[0]) && compact[1] === '사업명';
    const reason = row.merged ? '병합 셀을 확인하고 5개 열로 나누어 입력하세요.'
      : row.malformed ? '닫히지 않은 따옴표를 확인하세요.'
        : cells.length !== 5 ? '번호 / 사업명 / 사업기간 / 계약금액 / 발주처의 5개 셀이 필요합니다.'
          : cells.some(value => !value) ? '빈 필수 셀을 확인하세요.' : '';
    return { id: index, cells, reason, header, empty: cells.every(value => !value) };
  }).filter(row => !row.header && !row.empty);
}

export default function PerformanceImport({ projectId, onImported }) {
  const [text, setText] = useState('');
  const [html, setHtml] = useState('');
  const [errors, setErrors] = useState([]);
  const [result, setResult] = useState('');
  const [failure, setFailure] = useState('');
  const [saving, setSaving] = useState(false);
  const busy = useRef(false);
  const rows = useMemo(() => previewRows(text, html), [text, html]);
  function paste(event) {
    const rich = event.clipboardData.getData('text/html');
    const plain = event.clipboardData.getData('text/plain');
    if (rich && /<table[\s>]/i.test(rich)) {
      event.preventDefault(); setHtml(rich); setText(plain.trim() ? plain : tableText(htmlRows(rich)));
    } else if (plain) {
      event.preventDefault(); setHtml('');
      const target = event.currentTarget;
      setText(current => current.slice(0, target.selectionStart) + plain + current.slice(target.selectionEnd));
    }
  }
  async function save() {
    if (busy.current || !rows.length) return;
    busy.current = true; setSaving(true); setFailure('');
    try {
      const imported = await importPerformanceEntries(projectId, text, html || null);
      onImported(imported.saved); setErrors(imported.errors.map((error, index) => ({ ...error, id: `${Date.now()}-${index}` })));
      setResult(`${imported.saved.length}행 저장, ${imported.errors.length}행 확인 필요`);
      setText(''); setHtml('');
    } catch (error) { setFailure(error.message); }
    finally { busy.current = false; setSaving(false); }
  }
  async function retry(row) {
    if (busy.current) return;
    busy.current = true; setSaving(true); setFailure('');
    try {
      const values = Object.fromEntries(names.map((name, index) => [name, row.cells[index] || '']));
      const saved = await createPerformanceEntry(projectId, values);
      onImported([saved]); setErrors(current => current.filter(item => item.id !== row.id));
    } catch (error) {
      setErrors(current => current.map(item => item.id === row.id ? { ...item, message: error.message } : item));
    } finally { busy.current = false; setSaving(false); }
  }
  return <details className="performance-import">
    <summary>실적표 일괄 붙여넣기</summary>
    <div className="performance-import-body">
      <label htmlFor="performance-paste">PPT 실적표</label>
      <textarea id="performance-paste" rows="4" value={text} disabled={saving} onPaste={paste}
        placeholder="번호 / 사업명 / 사업기간 / 계약금액 / 발주처"
        onChange={event => { setText(event.target.value); setHtml(''); setFailure(''); }} />
      {rows.length > 0 && <div className="performance-import-scroll" tabIndex={0} aria-label="실적표 붙여넣기 미리보기">
        <table><thead><tr>{labels.map(label => <th key={label}>{label}</th>)}<th>확인</th></tr></thead>
          <tbody>{rows.map(row => <tr key={row.id} className={row.reason ? 'paste-preview-error' : undefined}>
            {Array.from({ length: 5 }, (_, index) => <td key={index}>{(index === 4 ? row.cells.slice(index).join(' / ') : row.cells[index]) || '—'}</td>)}
            <td>{row.reason || '정상'}</td></tr>)}</tbody></table>
      </div>}
      {rows.length > 0 && <p role="status">{rows.length}행 · 확인 필요 {rows.filter(row => row.reason).length}행</p>}
      {failure && <p role="alert">{failure}</p>}{result && <p className="performance-import-result">{result}</p>}
      <Button className="ui-button ui-button-primary" disabled={saving || !rows.length} onClick={save}>{saving ? '저장 중…' : '일괄 저장'}</Button>
      {errors.length > 0 && <div className="performance-import-errors"><h3>확인 필요 행</h3>
        {errors.map(row => <form key={row.id} onSubmit={event => { event.preventDefault(); retry(row); }}>
          <div className="performance-import-error-fields">{names.map((name, index) => <label key={name}>{labels[index]}
            <input value={row.cells?.[index] || ''} disabled={saving} onChange={event => setErrors(current => current.map(item => item.id === row.id
              ? { ...item, cells: names.map((_, cell) => cell === index ? event.target.value : item.cells?.[cell] || '') } : item))} />
          </label>)}</div>
          <p role="alert">{row.message}</p><Button className="ui-button ui-button-secondary" type="submit" disabled={saving}>수정 행 저장</Button>
        </form>)}
      </div>}
    </div>
  </details>;
}
