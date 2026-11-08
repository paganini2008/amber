import type { ErColumn, ErEntity, ErModel, ErRelationship } from '@shared/protocol'

/**
 * ER diagram (`er.model`): which relationships and tables to show, how big each entity card is, and
 * what changed when metadata arrived. Pure, so the view and the SVG export draw the same thing.
 * Relationships always point from the "many" side to the "one" side.
 */

export type Confidence = 'CERTAIN' | 'INFERRED' | 'POSSIBLE'
export const CONFIDENCE_RANK: Record<string, number> = { POSSIBLE: 0, INFERRED: 1, CERTAIN: 2 }
export const EVIDENCE = ['DECLARED_FK', 'METADATA_FK', 'JOIN', 'NAME_MATCH', 'DATA_FLOW'] as const
export type Evidence = (typeof EVIDENCE)[number]

export interface ErFilters {
  /** Evidence kinds to show. */
  evidence: ReadonlySet<string>
  /** The weakest confidence still shown. */
  minConfidence: Confidence
  /** Only entities within `hops` relationships of `entity`. */
  around: { entity: string; hops: number } | null
}

export const DEFAULT_FILTERS: ErFilters = { evidence: new Set(EVIDENCE), minConfidence: 'POSSIBLE', around: null }

export interface ErView {
  /** Entities with at least one shown relationship (or the "around" entity itself). */
  entities: ErEntity[]
  relationships: ErRelationship[]
  /** Entities without any shown relationship: drawn as one collapsed list, not as cards. */
  isolated: ErEntity[]
}

/** Applies evidence, confidence and "around this table" filters. */
export function filterEr(model: ErModel, filters: ErFilters): ErView {
  const minRank = CONFIDENCE_RANK[filters.minConfidence] ?? 0
  let relationships = model.relationships.filter(
    (r) => filters.evidence.has(r.evidence) && (CONFIDENCE_RANK[r.confidence] ?? 0) >= minRank && r.from.entity !== r.to.entity
  )
  let keep: Set<string> | null = null
  if (filters.around && model.entities.some((e) => e.key === filters.around!.entity)) {
    keep = new Set([filters.around.entity])
    let frontier = [filters.around.entity]
    for (let hop = 0; hop < filters.around.hops && frontier.length; hop++) {
      const next: string[] = []
      for (const r of relationships) {
        for (const [a, b] of [
          [r.from.entity, r.to.entity],
          [r.to.entity, r.from.entity]
        ] as const) {
          if (frontier.includes(a) && !keep.has(b)) {
            keep.add(b)
            next.push(b)
          }
        }
      }
      frontier = next
    }
    relationships = relationships.filter((r) => keep!.has(r.from.entity) && keep!.has(r.to.entity))
  }
  const linked = new Set(relationships.flatMap((r) => [r.from.entity, r.to.entity]))
  if (filters.around) linked.add(filters.around.entity)
  const entities: ErEntity[] = []
  const isolated: ErEntity[] = []
  for (const e of model.entities) {
    if (keep && !keep.has(e.key)) continue
    if (linked.has(e.key)) entities.push(e)
    else isolated.push(e)
  }
  return { entities, relationships, isolated }
}

/** Relationships that touch an entity. */
export function relationshipsOf(relationships: ErRelationship[], entity: string): ErRelationship[] {
  return relationships.filter((r) => r.from.entity === entity || r.to.entity === entity)
}

/** Key columns first (primary key, foreign key, joined), then the rest in table order. */
export function orderedColumns(entity: ErEntity): { keys: ErColumn[]; rest: ErColumn[] } {
  const keys = entity.columns.filter((c) => c.pk || c.fk || c.joined)
  const rank = (c: ErColumn): number => (c.pk ? 0 : c.fk ? 1 : 2)
  return { keys: [...keys].sort((a, b) => rank(a) - rank(b)), rest: entity.columns.filter((c) => !(c.pk || c.fk || c.joined)) }
}

export const CARD = { WIDTH: 236, HEADER: 44, ROW: 22, FOOTER: 22 }

export interface ErCardModel {
  entity: ErEntity
  rows: ErColumn[]
  /** Columns not shown (collapsed). */
  hidden: number
  width: number
  height: number
}

/** A card shows the key columns; the rest appear when expanded. */
export function cardModel(entity: ErEntity, expanded: boolean): ErCardModel {
  const { keys, rest } = orderedColumns(entity)
  const rows = expanded ? [...keys, ...rest] : keys
  const hidden = expanded ? 0 : rest.length
  const longest = Math.max(entity.name.length, ...rows.map((c) => c.name.length + (c.type ? c.type.length + 2 : 0)))
  const width = Math.min(320, Math.max(CARD.WIDTH, 40 + longest * 7.2))
  const height = CARD.HEADER + (rows.length || (entity.columns.length ? 0 : 1)) * CARD.ROW + (hidden > 0 || (expanded && rest.length > 0) ? CARD.FOOTER : 0) + 4
  return { entity, rows, hidden, width: Math.round(width), height }
}

/** The schema of a qualified name ("sales.orders" → "sales"), or "" without one. */
export function schemaOf(name: string): string {
  const i = name.lastIndexOf('.')
  return i > 0 ? name.slice(0, i) : ''
}

/** Entities ordered by schema then name, so the layout keeps a schema's tables together. */
export function bySchema(entities: ErEntity[]): ErEntity[] {
  return [...entities].sort((a, b) => schemaOf(a.name).localeCompare(schemaOf(b.name)) || a.name.localeCompare(b.name))
}

/** Stroke dash per confidence: solid (certain), dashed (inferred), dotted (possible). */
export function dashFor(confidence: string): string | undefined {
  if (confidence === 'CERTAIN') return undefined
  if (confidence === 'INFERRED') return '6 4'
  return '1.5 4.5'
}

/** The ends of a relationship line: crow's foot ("many"), bar ("one") or an arrow (data flow, cardinality unknown). */
export function ends(r: Pick<ErRelationship, 'cardinality' | 'evidence'>): { from: 'many' | 'one' | 'none'; to: 'many' | 'one' | 'arrow' | 'none' } {
  switch (r.cardinality) {
    case 'MANY_TO_ONE':
      return { from: 'many', to: 'one' }
    case 'ONE_TO_ONE':
      return { from: 'one', to: 'one' }
    case 'MANY_TO_MANY':
      return { from: 'many', to: 'many' }
    case 'ONE_TO_MANY':
      return { from: 'one', to: 'many' }
    default:
      return { from: 'none', to: r.evidence === 'DATA_FLOW' ? 'arrow' : 'none' }
  }
}

/** Identifies a relationship across two engine answers (ids are per answer). */
export function relationshipKey(r: Pick<ErRelationship, 'from' | 'to'>): string {
  const side = (s: { entity: string; columns: string[] }): string => `${s.entity}(${s.columns.map((c) => c.toLowerCase()).join(',')})`
  return `${side(r.from)}>${side(r.to)}`
}

/** Relationships whose confidence rose between two answers (e.g. online metadata confirmed a join). */
export function upgradedRelationships(before: ErModel, after: ErModel): ErRelationship[] {
  const old = new Map(before.relationships.map((r) => [relationshipKey(r), r]))
  return after.relationships.filter((r) => {
    const o = old.get(relationshipKey(r))
    return o !== undefined && (CONFIDENCE_RANK[r.confidence] ?? 0) > (CONFIDENCE_RANK[o.confidence] ?? 0)
  })
}

/** Entities whose name matches every word of a search (case-insensitive). */
export function searchEntities(entities: ErEntity[], query: string): ErEntity[] {
  const words = query.toLowerCase().split(/\s+/).filter(Boolean)
  if (!words.length) return []
  return entities.filter((e) => words.every((w) => e.name.toLowerCase().includes(w)))
}
