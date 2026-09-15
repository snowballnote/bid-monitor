import { useEffect, useLayoutEffect, useRef, useState } from 'react';

export default function ProjectMenu({ project, open, disabled, onToggle, onClose, onEdit, onDelete }) {
  const wrapper = useRef(null);
  const toggle = useRef(null);
  const menu = useRef(null);
  const [position, setPosition] = useState({ left: 8, top: 8 });
  useLayoutEffect(() => {
    if (!open) return;
    const rect = toggle.current.getBoundingClientRect();
    setPosition({ left: Math.max(8, rect.right - menu.current.offsetWidth),
      top: Math.max(8, Math.min(rect.bottom + 4, innerHeight - menu.current.offsetHeight - 8)) });
    menu.current.querySelector('button')?.focus({ preventScroll: true });
  }, [open]);
  useEffect(() => {
    if (!open) return;
    const outside = event => { if (!wrapper.current?.contains(event.target)) onClose(); };
    const reposition = () => {
      const rect = toggle.current.getBoundingClientRect();
      if (rect.bottom <= 0 || rect.top >= innerHeight) { onClose(); return; }
      setPosition({ left: Math.max(8, rect.right - menu.current.offsetWidth),
        top: Math.max(8, Math.min(rect.bottom + 4, innerHeight - menu.current.offsetHeight - 8)) });
    };
    document.addEventListener('pointerdown', outside);
    document.addEventListener('focusin', outside);
    window.addEventListener('resize', reposition);
    window.addEventListener('scroll', reposition, true);
    return () => {
      document.removeEventListener('pointerdown', outside);
      document.removeEventListener('focusin', outside);
      window.removeEventListener('resize', reposition);
      window.removeEventListener('scroll', reposition, true);
    };
  }, [open, onClose]);
  function keyDown(event) {
    event.stopPropagation();
    if (event.key === 'Escape') { event.preventDefault(); onClose(); toggle.current.focus(); }
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault();
      if (!open) { onToggle(); return; }
      const items = [...menu.current.querySelectorAll('button')];
      const index = items.indexOf(document.activeElement);
      items[(index + (event.key === 'ArrowDown' ? 1 : -1) + items.length) % items.length].focus();
    }
  }
  return <div className="project-menu" ref={wrapper} onClick={event => event.stopPropagation()} onKeyDown={keyDown}>
    <button ref={toggle} type="button" className="project-menu-toggle" aria-label={`${project.projectName} 메뉴`}
      aria-expanded={open} aria-haspopup="menu" disabled={disabled} onClick={onToggle}>⋮</button>
    {open && <div ref={menu} className="project-menu-items" role="menu" style={position}>
      {[['수정', 'edit', onEdit, 'M14 5l5 5M4 20l4-1L20 7a2 2 0 0 0-4-4L4 15z'],
        ['삭제', 'delete', onDelete, 'M3 6h18M9 6V3h6v3M5 6l1 15h12l1-15M10 10v7M14 10v7']].map(([label, kind, action, path]) =>
        <button key={kind} type="button" role="menuitem" className={`project-menu-${kind}`} disabled={disabled}
          onClick={() => { onClose(); toggle.current.focus(); action(project); }}>
          <svg viewBox="0 0 24 24" aria-hidden="true" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round"><path d={path} /></svg>
          <span>{label}</span>
        </button>)}
    </div>}
  </div>;
}
