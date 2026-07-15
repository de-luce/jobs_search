/** 人工修改投递状态（分析列表） */
export async function updateDeliveryStatus(
  url: string,
  status: string
): Promise<{ ok: boolean; message: string }> {
  try {
    const res = await fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ status }),
    })
    const data = (await res.json().catch(() => ({}))) as {
      success?: boolean
      message?: string
    }
    if (!res.ok) {
      return {
        ok: false,
        message: data.message || `请求失败（HTTP ${res.status}），请重启后端后再试`,
      }
    }
    if (!data.success) {
      return { ok: false, message: data.message || '更新失败' }
    }
    return { ok: true, message: data.message || '更新成功' }
  } catch (e) {
    return {
      ok: false,
      message: e instanceof Error ? e.message : '更新失败',
    }
  }
}
