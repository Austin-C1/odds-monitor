import type { ReactNode } from 'react'
import './PageShell.css'

type PageShellProps = {
  title: string
  description?: string
  actions?: ReactNode
  children: ReactNode
  className?: string
}

export const PageShell = ({
  title,
  description,
  actions,
  children,
  className = '',
}: PageShellProps) => (
  <section className={`page-shell ${className}`.trim()}>
    <header className="page-shell-header">
      <div className="page-shell-title">
        <h1>{title}</h1>
        {description ? <p>{description}</p> : null}
      </div>
      {actions ? <div className="page-shell-actions">{actions}</div> : null}
    </header>
    <div className="page-shell-body">
      {children}
    </div>
  </section>
)

export default PageShell
