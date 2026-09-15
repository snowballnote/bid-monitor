export default function StatusBadge({ tone = '', children }) {
  return <span className={`work-badge${tone ? ` ${tone}` : ''}`}>{children}</span>;
}
