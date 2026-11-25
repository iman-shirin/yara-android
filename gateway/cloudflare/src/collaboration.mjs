const encoder = new TextEncoder();
const TASK_STATES = new Set(['queued','running','completed','failed','cancelled']);
const MESSAGE_KINDS = new Set(['message','task_update','result','request']);
export const byteLength = (value) => encoder.encode(value).length;

export function validateTaskPayload(body) {
  if (!body || typeof body !== 'object' || Array.isArray(body)) return { ok:false,error:'invalid_task' };
  if (typeof body.title !== 'string' || body.title.trim().length < 3 || body.title.length > 200) return { ok:false,error:'invalid_title' };
  if (typeof body.description !== 'string' || !body.description.trim() || byteLength(body.description) > 20000) return { ok:false,error:'invalid_description' };
  if (body.assignedTo !== undefined && body.assignedTo !== null && (typeof body.assignedTo !== 'string' || !/^[a-z][a-z0-9-]{1,47}$/.test(body.assignedTo))) return { ok:false,error:'invalid_assignee' };
  const maxAttempts = body.maxAttempts ?? 3;
  if (!Number.isInteger(maxAttempts) || maxAttempts < 1 || maxAttempts > 10) return { ok:false,error:'invalid_max_attempts' };
  return { ok:true,value:{ title:body.title.trim(),description:body.description,assignedTo:body.assignedTo ?? null,maxAttempts } };
}

export function validateMessagePayload(body) {
  if (!body || typeof body !== 'object' || Array.isArray(body)) return { ok:false,error:'invalid_message' };
  if (body.recipientId !== undefined && body.recipientId !== null && (typeof body.recipientId !== 'string' || !/^[a-z][a-z0-9-]{1,47}$/.test(body.recipientId))) return { ok:false,error:'invalid_recipient' };
  if (body.taskId !== undefined && body.taskId !== null && (typeof body.taskId !== 'string' || !/^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(body.taskId))) return { ok:false,error:'invalid_task_id' };
  const kind = body.kind ?? 'message';
  if (!MESSAGE_KINDS.has(kind)) return { ok:false,error:'invalid_message_kind' };
  if (body.idempotencyKey !== undefined && body.idempotencyKey !== null && (typeof body.idempotencyKey !== 'string' || body.idempotencyKey.length > 128)) return { ok:false,error:'invalid_idempotency_key' };
  let serialized; try { serialized = JSON.stringify(body.body); } catch { return { ok:false,error:'invalid_message_body' }; }
  if (body.body === undefined || byteLength(serialized) > 50000) return { ok:false,error:'invalid_message_body' };
  return { ok:true,value:{ recipientId:body.recipientId ?? null,taskId:body.taskId ?? null,kind,body:body.body,idempotencyKey:body.idempotencyKey ?? null } };
}

export { TASK_STATES, MESSAGE_KINDS };
