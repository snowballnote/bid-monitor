export default function Button({ href, children, className = 'work-action', ...props }) {
  return href ? <a className={className} href={href} {...props}>{children}</a>
    : <button type="button" className={className} {...props}>{children}</button>;
}
