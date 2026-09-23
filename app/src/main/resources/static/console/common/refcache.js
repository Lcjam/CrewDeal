// refcache.js — 기존 단건 API의 N+1 완화 (프론트엔드 계획 §5.8)
//
// OrderResponse는 campaignId·items[].productSkuId만 준다. CampaignResponse가 name과
// skus[].optionName을 가지므로 캠페인당 한 번만 조회해 페이지 수명 동안 메모리 Map에 둔다.
// 진행 중인 요청은 Promise를 캐시해 동시 호출을 dedupe한다.
// 세션 스토리지에 영속화하지 않는다 — 이름은 불변이지만 상태는 스케줄러가 계속 바꾼다.

import { api } from './api.js';

/** @type {Map<number, Promise<object>>} campaignId -> CampaignResponse promise */
const campaignPromises = new Map();

function fetchCampaign(campaignId) {
  if (!campaignPromises.has(campaignId)) {
    const promise = api(`/api/campaigns/${campaignId}`)
      .then(({ data }) => data)
      .catch((err) => {
        // 실패한 요청은 캐시에 남기지 않는다 — 다음 호출이 재시도할 수 있게.
        campaignPromises.delete(campaignId);
        throw err;
      });
    campaignPromises.set(campaignId, promise);
  }
  return campaignPromises.get(campaignId);
}

/** 캠페인 이름. */
export async function campaignName(campaignId) {
  const campaign = await fetchCampaign(campaignId);
  return campaign.name;
}

/** 캠페인 내 SKU 옵션명. */
export async function skuOptionName(campaignId, productSkuId) {
  const campaign = await fetchCampaign(campaignId);
  const sku = (campaign.skus ?? []).find((s) => s.productSkuId === productSkuId);
  return sku ? sku.optionName : String(productSkuId);
}
