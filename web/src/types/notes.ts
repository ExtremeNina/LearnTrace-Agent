/**
 * 笔记分层树节点：分组（group）可嵌套（最多 5 层），笔记（note）为叶子
 */
export interface TreeNodeData {
  id: string
  name: string
  type: 'group' | 'note'
  noteId?: number
  children?: TreeNodeData[]
}
