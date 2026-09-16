import { NavLink, Outlet } from 'react-router-dom';

const menus = [['/bids/', '입찰공고'], ['/notices/', '외부 중요공지'],
  ['/notifications/', '알림 관리'], ['/submissions/', '서류 모으기'], ['/documents/', '서류 관리']];

export default function AppLayout() {
  return <div className="app-shell">
    <aside className="app-sidebar">
      <NavLink className="app-brand" to="/" aria-label="Biz Assist 홈">
        <span className="app-brand-mark" aria-hidden="true">BA</span>
        <span><strong>Biz Assist</strong><small>경영지원 업무지원</small></span>
      </NavLink>
      <nav className="app-nav" aria-label="주요 메뉴">
        <NavLink className={({ isActive }) => `app-nav-link${isActive ? ' active' : ''}`} to="/" end>홈</NavLink>
        {menus.map(([href, label]) => ['/submissions/', '/documents/'].includes(href)
          ? <NavLink key={href} to={href.slice(0, -1)} className={({ isActive }) => `app-nav-link${isActive ? ' active' : ''}`}>{label}</NavLink>
          : <a className="app-nav-link" href={href} key={href}>{label}</a>)}
      </nav>
      <p className="app-sidebar-description">경영지원에 필요한 정보를 한곳에서 확인하세요.</p>
    </aside>
    <div className="app-main"><Outlet /></div>
  </div>;
}
