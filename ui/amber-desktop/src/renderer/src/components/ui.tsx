import * as DialogPrimitive from '@radix-ui/react-dialog'
import * as DropdownPrimitive from '@radix-ui/react-dropdown-menu'
import * as SelectPrimitive from '@radix-ui/react-select'
import * as SwitchPrimitive from '@radix-ui/react-switch'
import * as TooltipPrimitive from '@radix-ui/react-tooltip'
import clsx from 'clsx'
import { Check, ChevronDown, ChevronRight, X } from 'lucide-react'
import { forwardRef, type ButtonHTMLAttributes, type ReactNode } from 'react'

type Variant = 'primary' | 'default' | 'ghost' | 'danger'

const VARIANTS: Record<Variant, string> = {
  primary: 'bg-amber text-on-amber hover:brightness-105 active:brightness-95 font-medium',
  default: 'bg-surface text-ink border border-line hover:bg-surface-hover',
  ghost: 'text-ink hover:bg-surface-hover',
  danger: 'bg-surface text-risk-high border border-line hover:bg-surface-hover'
}

export interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: Variant
  size?: 'sm' | 'md'
}

export const Button = forwardRef<HTMLButtonElement, ButtonProps>(function Button(
  { variant = 'default', size = 'md', className, type = 'button', ...props },
  ref
) {
  return (
    <button
      ref={ref}
      type={type}
      className={clsx(
        'no-drag inline-flex items-center justify-center gap-1.5 rounded-[5px] whitespace-nowrap select-none',
        'disabled:pointer-events-none disabled:opacity-45 transition-colors',
        size === 'sm' ? 'h-6 px-2 text-xs' : 'h-7 px-2.5 text-sm',
        VARIANTS[variant],
        className
      )}
      {...props}
    />
  )
})

export const IconButton = forwardRef<HTMLButtonElement, ButtonProps & { label: string; active?: boolean }>(
  function IconButton({ label, active, className, children, ...props }, ref) {
    return (
      <Tooltip content={label}>
        <button
          ref={ref}
          type="button"
          aria-label={label}
          aria-pressed={active}
          className={clsx(
            'no-drag inline-flex h-7 w-7 items-center justify-center rounded-[5px] text-ink-muted transition-colors',
            'hover:bg-surface-hover hover:text-ink disabled:pointer-events-none disabled:opacity-40',
            active && 'bg-amber-wash text-amber-ink',
            className
          )}
          {...props}
        >
          {children}
        </button>
      </Tooltip>
    )
  }
)

export function Tooltip({ content, children, side = 'bottom' }: { content: ReactNode; children: ReactNode; side?: 'top' | 'bottom' | 'left' | 'right' }): React.JSX.Element {
  return (
    <TooltipPrimitive.Root delayDuration={400}>
      <TooltipPrimitive.Trigger asChild>{children}</TooltipPrimitive.Trigger>
      <TooltipPrimitive.Portal>
        <TooltipPrimitive.Content
          side={side}
          sideOffset={6}
          className="z-50 max-w-80 rounded-[5px] border border-line bg-surface px-2 py-1 text-xs text-ink shadow-[0_4px_16px_rgba(0,0,0,0.12)]"
        >
          {content}
        </TooltipPrimitive.Content>
      </TooltipPrimitive.Portal>
    </TooltipPrimitive.Root>
  )
}

export const TooltipProvider = TooltipPrimitive.Provider

export function Dialog({
  open,
  onOpenChange,
  title,
  description,
  children,
  width = 560,
  footer
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  title: string
  description?: string
  children: ReactNode
  width?: number
  footer?: ReactNode
}): React.JSX.Element {
  return (
    <DialogPrimitive.Root open={open} onOpenChange={onOpenChange}>
      <DialogPrimitive.Portal>
        <DialogPrimitive.Overlay className="fixed inset-0 z-40 bg-[color-mix(in_srgb,var(--canvas)_55%,transparent)] backdrop-blur-[2px]" />
        <DialogPrimitive.Content
          style={{ width }}
          onEscapeKeyDown={(e) => {
            // Escape first closes an open suggestion list inside the dialog.
            if ((document.activeElement as HTMLElement | null)?.closest?.('[role="combobox"][aria-expanded="true"]')) e.preventDefault()
          }}
          onOpenAutoFocus={(e) => {
            // Focus the dialog itself, not its first button: a focused Close button opens its tooltip,
            // and the open tooltip would swallow the first Escape. Tab still reaches every control.
            e.preventDefault()
            ;(e.currentTarget as HTMLElement | null)?.focus()
          }}
          className="fixed top-1/2 left-1/2 z-50 flex max-h-[86vh] max-w-[calc(100vw-32px)] -translate-x-1/2 -translate-y-1/2 flex-col rounded-[8px] border border-line bg-surface text-ink shadow-[0_24px_64px_rgba(0,0,0,0.28)] outline-none"
        >
          <div className="flex items-start justify-between gap-4 border-b border-line px-5 pt-4 pb-3">
            <div>
              <DialogPrimitive.Title className="text-lg font-semibold">{title}</DialogPrimitive.Title>
              {description ? (
                <DialogPrimitive.Description className="mt-0.5 text-sm text-ink-muted">{description}</DialogPrimitive.Description>
              ) : (
                <DialogPrimitive.Description className="sr-only">{title}</DialogPrimitive.Description>
              )}
            </div>
            <DialogPrimitive.Close asChild>
              <IconButton label="Close">
                <X size={16} />
              </IconButton>
            </DialogPrimitive.Close>
          </div>
          <div className="min-h-0 flex-1 overflow-auto">{children}</div>
          {footer ? <div className="flex justify-end gap-2 border-t border-line px-5 py-3">{footer}</div> : null}
        </DialogPrimitive.Content>
      </DialogPrimitive.Portal>
    </DialogPrimitive.Root>
  )
}

export interface SelectOption {
  value: string
  label: string
}

export function Select({
  value,
  onChange,
  options,
  label,
  className,
  placeholder,
  disabled
}: {
  value: string
  onChange: (value: string) => void
  options: SelectOption[]
  label: string
  className?: string
  placeholder?: string
  disabled?: boolean
}): React.JSX.Element {
  return (
    <SelectPrimitive.Root value={value || undefined} onValueChange={onChange} disabled={disabled}>
      <SelectPrimitive.Trigger
        aria-label={label}
        className={clsx(
          'no-drag inline-flex h-7 items-center justify-between gap-1.5 rounded-[5px] border border-line bg-surface px-2 text-sm text-ink',
          'hover:bg-surface-hover disabled:opacity-45 data-[placeholder]:text-ink-muted',
          className
        )}
      >
        <SelectPrimitive.Value placeholder={placeholder} />
        <SelectPrimitive.Icon>
          <ChevronDown size={14} className="text-ink-muted" />
        </SelectPrimitive.Icon>
      </SelectPrimitive.Trigger>
      <SelectPrimitive.Portal>
        <SelectPrimitive.Content
          position="popper"
          sideOffset={4}
          className="z-50 max-h-80 min-w-[var(--radix-select-trigger-width)] overflow-hidden rounded-[6px] border border-line bg-surface text-ink shadow-[0_8px_28px_rgba(0,0,0,0.18)]"
        >
          <SelectPrimitive.Viewport className="p-1">
            {options.map((o) => (
              <SelectPrimitive.Item
                key={o.value}
                value={o.value}
                className="relative flex h-7 cursor-default items-center rounded-[4px] pr-2 pl-7 text-sm outline-none select-none data-[highlighted]:bg-surface-hover"
              >
                <SelectPrimitive.ItemIndicator className="absolute left-2 text-amber-ink">
                  <Check size={13} />
                </SelectPrimitive.ItemIndicator>
                <SelectPrimitive.ItemText>{o.label}</SelectPrimitive.ItemText>
              </SelectPrimitive.Item>
            ))}
          </SelectPrimitive.Viewport>
        </SelectPrimitive.Content>
      </SelectPrimitive.Portal>
    </SelectPrimitive.Root>
  )
}

export function Switch({
  checked,
  onChange,
  label,
  id
}: {
  checked: boolean
  onChange: (checked: boolean) => void
  label: string
  id?: string
}): React.JSX.Element {
  return (
    <SwitchPrimitive.Root
      id={id}
      checked={checked}
      onCheckedChange={onChange}
      aria-label={label}
      className="no-drag relative inline-flex h-[18px] w-[32px] shrink-0 items-center rounded-full border border-line-strong bg-surface-2 transition-colors data-[state=checked]:border-amber data-[state=checked]:bg-amber"
    >
      <SwitchPrimitive.Thumb className="block h-3 w-3 translate-x-[2px] rounded-full bg-ink-muted transition-transform duration-150 data-[state=checked]:translate-x-[16px] data-[state=checked]:bg-on-amber" />
    </SwitchPrimitive.Root>
  )
}

export const Menu = DropdownPrimitive.Root
export const MenuTrigger = DropdownPrimitive.Trigger

export function MenuContent({ children, align = 'start' }: { children: ReactNode; align?: 'start' | 'end' | 'center' }): React.JSX.Element {
  return (
    <DropdownPrimitive.Portal>
      <DropdownPrimitive.Content
        align={align}
        sideOffset={4}
        className="z-50 min-w-52 rounded-[6px] border border-line bg-surface p-1 text-ink shadow-[0_8px_28px_rgba(0,0,0,0.18)]"
      >
        {children}
      </DropdownPrimitive.Content>
    </DropdownPrimitive.Portal>
  )
}

export function MenuItem({
  children,
  onSelect,
  disabled,
  checked,
  hint,
  keepOpen
}: {
  children: ReactNode
  onSelect?: () => void
  disabled?: boolean
  checked?: boolean
  hint?: string
  /** Keep the menu open after selecting, e.g. for toggles in a multi-select list. */
  keepOpen?: boolean
}): React.JSX.Element {
  return (
    <DropdownPrimitive.Item
      disabled={disabled}
      onSelect={(e) => {
        if (keepOpen) e.preventDefault()
        onSelect?.()
      }}
      className="flex h-7 cursor-default items-center gap-2 rounded-[4px] pr-2 pl-2 text-sm outline-none select-none data-[disabled]:opacity-45 data-[highlighted]:bg-surface-hover"
    >
      {checked !== undefined ? (
        <span className="w-3.5 text-amber-ink">{checked ? <Check size={13} /> : null}</span>
      ) : null}
      <span className="flex min-w-0 flex-1 items-center gap-2 truncate">{children}</span>
      {hint ? <span className="text-xs text-ink-muted">{hint}</span> : null}
    </DropdownPrimitive.Item>
  )
}

export function MenuSub({ label, children }: { label: ReactNode; children: ReactNode }): React.JSX.Element {
  return (
    <DropdownPrimitive.Sub>
      <DropdownPrimitive.SubTrigger className="flex h-7 cursor-default items-center gap-2 rounded-[4px] px-2 text-sm outline-none select-none data-[highlighted]:bg-surface-hover data-[state=open]:bg-surface-hover">
        <span className="flex min-w-0 flex-1 items-center gap-2 truncate">{label}</span>
        <ChevronRight size={13} className="text-ink-muted" />
      </DropdownPrimitive.SubTrigger>
      <DropdownPrimitive.Portal>
        <DropdownPrimitive.SubContent
          sideOffset={4}
          className="z-50 min-w-52 rounded-[6px] border border-line bg-surface p-1 text-ink shadow-[0_8px_28px_rgba(0,0,0,0.18)]"
        >
          {children}
        </DropdownPrimitive.SubContent>
      </DropdownPrimitive.Portal>
    </DropdownPrimitive.Sub>
  )
}

export function MenuLabel({ children }: { children: ReactNode }): React.JSX.Element {
  return <DropdownPrimitive.Label className="px-2 pt-1.5 pb-1 text-xs text-ink-muted">{children}</DropdownPrimitive.Label>
}

export function MenuSeparator(): React.JSX.Element {
  return <DropdownPrimitive.Separator className="my-1 h-px bg-line" />
}

export function Field({ label, hint, children, htmlFor }: { label: string; hint?: ReactNode; children: ReactNode; htmlFor?: string }): React.JSX.Element {
  return (
    <div className="grid grid-cols-[180px_1fr] items-start gap-4 py-2">
      <label htmlFor={htmlFor} className="pt-1 text-sm text-ink">
        {label}
      </label>
      <div className="min-w-0">
        {children}
        {hint ? <div className="mt-1 text-xs text-ink-muted">{hint}</div> : null}
      </div>
    </div>
  )
}

export const inputClass =
  'no-drag h-7 w-full rounded-[5px] border border-line bg-surface px-2 text-sm text-ink placeholder:text-ink-faint outline-none focus:border-amber selectable'

export function Spinner({ size = 14 }: { size?: number }): React.JSX.Element {
  return (
    <svg width={size} height={size} viewBox="0 0 16 16" className="spin" aria-hidden>
      <circle cx="8" cy="8" r="6" fill="none" stroke="currentColor" strokeOpacity="0.25" strokeWidth="2" />
      <path d="M14 8a6 6 0 0 0-6-6" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
    </svg>
  )
}

export function NotAvailable({ feature, children }: { feature: string; children?: ReactNode }): React.JSX.Element {
  return (
    <div className="rounded-[6px] border border-dashed border-line-strong px-3 py-2.5 text-sm text-ink-muted">
      <div className="text-ink">{feature} is not available in this engine version.</div>
      {children ? <div className="mt-1">{children}</div> : null}
    </div>
  )
}
