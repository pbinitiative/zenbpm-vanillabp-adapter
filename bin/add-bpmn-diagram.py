#!/usr/bin/env python3
# Copyright (c) 2026 Process Builders Initiative
# SPDX-License-Identifier: MIT
#
# Gives BPMN files a generated diagram (decision 29 in the repository's DECISIONS.md):
#   bin/add-bpmn-diagram.py path/to/a.bpmn [more.bpmn ...]
# It lays the flow nodes of every process and sub-process out from left to right along their
# sequence flows, draws sub-processes expanded, puts boundary events on their activity and
# processes into their pools, and replaces a diagram the file already has. The model itself
# is left as it was written. A modeler draws nicer diagrams; this one makes a file readable.
"""Adds a BPMN diagram (shapes and edges) to a BPMN file, replacing any diagram it has.

The model text is left as it is; the diagram is generated from it and written before the closing
tag of the definitions, and the three DI namespaces are declared on the root element."""
import re
import sys
import xml.etree.ElementTree as ET

BPMN = 'http://www.omg.org/spec/BPMN/20100524/MODEL'
NS = {'bpmn': BPMN}
NOT_DRAWN = {'sequenceFlow', 'extensionElements', 'documentation', 'laneSet', 'dataObject', 'property',
             'ioSpecification', 'incoming', 'outgoing', 'multiInstanceLoopCharacteristics',
             'standardLoopCharacteristics', 'boundaryEvent'}
EVENTS = {'startEvent', 'endEvent', 'intermediateCatchEvent', 'intermediateThrowEvent', 'boundaryEvent'}
GATEWAYS = {'exclusiveGateway', 'parallelGateway', 'inclusiveGateway', 'eventBasedGateway', 'complexGateway'}
GAP_X, GAP_Y, PAD = 60, 50, 30


def local(tag):
    return tag.split('}', 1)[1] if '}' in tag else tag


def size(kind):
    if kind in EVENTS:
        return 36, 36
    if kind in GATEWAYS:
        return 50, 50
    return 100, 80


class Layout:
    def __init__(self):
        self.bounds = {}  # id -> (x, y, w, h)
        self.edges = []  # (id, [(x, y), ...])
        self.expanded = set()

    def container(self, element, x0, y0):
        """Lays out the children of a process or sub-process from (x0, y0); returns its size."""
        nodes = [c for c in element if local(c.tag) not in NOT_DRAWN and c.get('id')
                 and c.tag.startswith('{%s}' % BPMN)]
        flows = [c for c in element if local(c.tag) == 'sequenceFlow']
        ids = [n.get('id') for n in nodes]
        sizes = {}
        inner = {}
        for n in nodes:
            if local(n.tag) == 'subProcess':
                sub = Layout()
                w, h = sub.container(n, 0, 0)
                inner[n.get('id')] = sub
                sizes[n.get('id')] = (max(w + 2 * PAD, 200), max(h + 2 * PAD, 120))
            else:
                sizes[n.get('id')] = size(local(n.tag))
        # layers: longest path along the flows, isolated nodes on a row of their own
        succ = {i: [] for i in ids}
        pred = {i: [] for i in ids}
        for f in flows:
            s, t = f.get('sourceRef'), f.get('targetRef')
            if s in succ and t in succ and s != t:
                succ[s].append(t)
                pred[t].append(s)
        connected = [i for i in ids if succ[i] or pred[i]]
        layer = {}
        for i in connected:
            if not pred[i]:
                layer[i] = 0
        if connected and not layer:
            layer[connected[0]] = 0
        for _ in range(len(connected) + 1):
            changed = False
            for i in connected:
                if i in layer:
                    for t in succ[i]:
                        if layer.get(t, -1) < layer[i] + 1 and layer[i] + 1 <= len(connected):
                            layer[t] = layer[i] + 1
                            changed = True
            if not changed:
                break
        for i in connected:
            layer.setdefault(i, 0)
        columns = {}
        for i in connected:
            columns.setdefault(layer[i], []).append(i)
        x = x0
        bottom = y0
        right = x0
        for col in sorted(columns):
            members = columns[col]
            width = max(sizes[i][0] for i in members)
            y = y0
            for i in members:
                w, h = sizes[i]
                self.place(i, x + (width - w) // 2, y, w, h, inner)
                y += h + GAP_Y
            bottom = max(bottom, y - GAP_Y)
            x += width + GAP_X
            right = max(right, x - GAP_X)
        isolated = [i for i in ids if i not in layer]
        if isolated:
            y = bottom + (GAP_Y if connected else 0)
            row_height = max(sizes[i][1] for i in isolated)
            x = x0
            for i in isolated:
                w, h = sizes[i]
                self.place(i, x, y + (row_height - h) // 2, w, h, inner)
                x += w + GAP_X
            right = max(right, x - GAP_X)
            bottom = y + row_height
        # boundary events on the bottom edge of the activity they are attached to
        attached = {}
        for b in [c for c in element if local(c.tag) == 'boundaryEvent']:
            host = self.bounds.get(b.get('attachedToRef'))
            k = attached.get(b.get('attachedToRef'), 0)
            attached[b.get('attachedToRef')] = k + 1
            if host:
                hx, hy, hw, hh = host
                self.bounds[b.get('id')] = (hx + hw - 26 - 40 * k, hy + hh - 18, 36, 36)
            else:
                self.bounds[b.get('id')] = (right + GAP_X, y0, 36, 36)
                right += GAP_X + 36
            bottom = max(bottom, self.bounds[b.get('id')][1] + 36)
        for f in flows:
            self.edges.append((f.get('id'), self.waypoints(f.get('sourceRef'), f.get('targetRef'), x0, y0)))
        return right - x0, bottom - y0

    def place(self, i, x, y, w, h, inner):
        self.bounds[i] = (x, y, w, h)
        if i in inner:
            self.expanded.add(i)
            sub = inner[i]
            for j, (bx, by, bw, bh) in sub.bounds.items():
                self.bounds[j] = (bx + x + PAD, by + y + PAD, bw, bh)
            self.expanded |= sub.expanded
            for e, points in sub.edges:
                self.edges.append((e, [(px + x + PAD, py + y + PAD) for px, py in points]))

    def waypoints(self, source, target, x0, y0):
        s = self.bounds.get(source)
        t = self.bounds.get(target)
        if not s and not t:
            return [(x0, y0), (x0 + 50, y0)]
        if not s:
            tx, ty, tw, th = t
            return [(tx - 50, ty + th // 2), (tx, ty + th // 2)]
        if not t:
            sx, sy, sw, sh = s
            return [(sx + sw, sy + sh // 2), (sx + sw + 50, sy + sh // 2)]
        sx, sy, sw, sh = s
        tx, ty, tw, th = t
        start = (sx + sw, sy + sh // 2)
        end = (tx, ty + th // 2)
        if start[1] == end[1] or tx < sx + sw:
            if tx < sx + sw:
                below = max(sy + sh, ty + th) + 20
                return [(sx + sw // 2, sy + sh), (sx + sw // 2, below), (tx + tw // 2, below), (tx + tw // 2, ty + th)]
            return [start, end]
        middle = (start[0] + end[0]) // 2
        return [start, (middle, start[1]), (middle, end[1]), end]


def diagram(root):
    planes = []
    collaboration = root.find('bpmn:collaboration', NS)
    processes = {p.get('id'): p for p in root.findall('bpmn:process', NS)}
    if collaboration is not None:
        layout = Layout()
        pools = []
        y = 80
        for participant in collaboration.findall('bpmn:participant', NS):
            process = processes.pop(participant.get('processRef'), None)
            if process is None:
                pools.append((participant.get('id'), 160, y, 600, 100))
                y += 100 + GAP_Y
                continue
            w, h = layout.container(process, 160 + 30 + PAD, y + PAD)
            width, height = max(w + 30 + 2 * PAD, 600), max(h + 2 * PAD, 100)
            pools.append((participant.get('id'), 160, y, width, height))
            y += height + GAP_Y
        planes.append((collaboration.get('id'), layout, pools))
    for pid, process in processes.items():
        layout = Layout()
        layout.container(process, 160, 80)
        planes.append((pid, layout, []))
    out = []
    for n, (element, layout, pools) in enumerate(planes, start=1):
        suffix = '' if n == 1 else '_%d' % n
        out.append('  <bpmndi:BPMNDiagram id="BPMNDiagram_1%s">' % suffix)
        out.append('    <bpmndi:BPMNPlane id="BPMNPlane_1%s" bpmnElement="%s">' % (suffix, element))
        for pool, x, y, w, h in pools:
            out.append('      <bpmndi:BPMNShape id="%s_di" bpmnElement="%s" isHorizontal="true">' % (pool, pool))
            out.append('        <dc:Bounds x="%d" y="%d" width="%d" height="%d" />' % (x, y, w, h))
            out.append('      </bpmndi:BPMNShape>')
        for i, (x, y, w, h) in layout.bounds.items():
            extra = ' isExpanded="true"' if i in layout.expanded else ''
            out.append('      <bpmndi:BPMNShape id="%s_di" bpmnElement="%s"%s>' % (i, i, extra))
            out.append('        <dc:Bounds x="%d" y="%d" width="%d" height="%d" />' % (x, y, w, h))
            out.append('      </bpmndi:BPMNShape>')
        for e, points in layout.edges:
            out.append('      <bpmndi:BPMNEdge id="%s_di" bpmnElement="%s">' % (e, e))
            for px, py in points:
                out.append('        <di:waypoint x="%d" y="%d" />' % (px, py))
            out.append('      </bpmndi:BPMNEdge>')
        out.append('    </bpmndi:BPMNPlane>')
        out.append('  </bpmndi:BPMNDiagram>')
    return '\n'.join(out)


def main(path):
    text = open(path, encoding='utf-8').read()
    text = re.sub(r'\n?[ \t]*<bpmndi:BPMNDiagram\b.*?</bpmndi:BPMNDiagram>', '', text, flags=re.S)
    parsable = re.sub(r'<!DOCTYPE[^\[>]*(\[.*?\])?\s*>', '', text, flags=re.S)
    parsable = re.sub(r'&(?!lt;|gt;|amp;|quot;|apos;|#)\w+;', '', parsable)
    root = ET.fromstring(parsable.encode('utf-8'))
    block = diagram(root)
    match = re.search(r'<(\w+:)?definitions\b[^>]*>', text, flags=re.S)
    start = match.group(0)
    declarations = ''
    for prefix, uri in (('bpmndi', 'http://www.omg.org/spec/BPMN/20100524/DI'),
                        ('dc', 'http://www.omg.org/spec/DD/20100524/DC'),
                        ('di', 'http://www.omg.org/spec/DD/20100524/DI')):
        if 'xmlns:%s=' % prefix not in start:
            declarations += '\n    xmlns:%s="%s"' % (prefix, uri)
    if declarations:
        new_start = start[:-1].rstrip()
        closing = '/>' if new_start.endswith('/') else '>'
        new_start = new_start.rstrip('/').rstrip() + declarations + closing
        text = text.replace(start, new_start, 1)
    closing = re.search(r'</(\w+:)?definitions>\s*$', text)
    text = text[:closing.start()].rstrip('\n') + '\n' + block + '\n' + text[closing.start():]
    open(path, 'w', encoding='utf-8').write(text)


if __name__ == '__main__':
    if len(sys.argv) < 2 or any(argument.startswith('-') for argument in sys.argv[1:]):
        print('usage: bin/add-bpmn-diagram.py path/to/a.bpmn [more.bpmn ...]')
        sys.exit(0 if len(sys.argv) > 1 else 2)
    for p in sys.argv[1:]:
        main(p)
        print('diagram added:', p)
