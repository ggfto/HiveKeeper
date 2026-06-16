import { useMemo } from 'react'
import { ReactFlow, Background, Controls, Handle, Position } from '@xyflow/react'
import '@xyflow/react/dist/style.css'
import { Wifi, Boxes } from 'lucide-react'

/** A site: the left-column anchor that its APs hang off. */
function SiteNode({ data }) {
  return (
    <div className="rounded-lg border border-border bg-card px-3 py-2 shadow-sm">
      <div className="flex items-center gap-2">
        <Boxes className="h-4 w-4 text-primary" />
        <span className="text-sm font-semibold">{data.label}</span>
      </div>
      <div className="text-xs text-muted-foreground">
        {data.apCount} AP{data.apCount === 1 ? '' : 's'}
      </div>
      <Handle type="source" position={Position.Right} />
    </div>
  )
}

/** An AP (managed device): online dot, model + agent + hive, and the live client count. */
function ApNode({ data }) {
  return (
    <div
      className={`min-w-[180px] rounded-lg border bg-card px-3 py-2 shadow-sm ${
        data.online ? 'border-primary/50' : 'border-border opacity-70'
      }`}
    >
      <Handle type="target" position={Position.Left} />
      <div className="flex items-center gap-2">
        <Wifi className={`h-4 w-4 ${data.online ? 'text-primary' : 'text-muted-foreground'}`} />
        <span className="truncate text-sm font-medium">{data.label}</span>
        <span
          className={`ml-auto h-2 w-2 shrink-0 rounded-full ${data.online ? 'bg-green-500' : 'bg-muted-foreground'}`}
          aria-label={data.online ? 'online' : 'offline'}
        />
      </div>
      <div className="mt-0.5 flex flex-wrap items-center gap-x-2 text-xs text-muted-foreground">
        {data.model && <span>{data.model}</span>}
        {data.agentId && <span>· {data.agentId}</span>}
        {data.hive && <span>· hive {data.hive}</span>}
      </div>
      <div className="text-xs font-medium">
        {data.clientCount != null
          ? `${data.clientCount} client${data.clientCount === 1 ? '' : 's'}`
          : data.online
            ? '—'
            : 'unreachable'}
      </div>
      <Handle type="source" position={Position.Right} />
    </div>
  )
}

const NODE_TYPES = { site: SiteNode, ap: ApNode }

/**
 * The infrastructure map: sites on the left, their APs on the right, a solid edge site -> AP and a dashed
 * "mesh" edge between APs sharing a hive. Nodes + positions are computed by ../../lib/topology (pure, tested);
 * this is the React Flow canvas around them. Clicking an AP opens its device page.
 */
export function InfraMap({ nodes, edges, onSelectDevice }) {
  const styledEdges = useMemo(
    () =>
      (edges || []).map((e) =>
        e.data?.mesh
          ? { ...e, animated: true, label: 'mesh', style: { strokeDasharray: '5 5' } }
          : e,
      ),
    [edges],
  )

  return (
    <div className="h-[72vh] w-full rounded-lg border border-border">
      <ReactFlow
        nodes={nodes}
        edges={styledEdges}
        nodeTypes={NODE_TYPES}
        fitView
        nodesConnectable={false}
        onNodeClick={(_, node) => node.type === 'ap' && onSelectDevice?.(node.data.deviceId)}
      >
        <Background />
        <Controls showInteractive={false} />
      </ReactFlow>
    </div>
  )
}
