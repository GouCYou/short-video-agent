import {http} from './api';
import type {Brief,Conversation} from './conversation-types';
export const creationSettingsApi={
  get:async()=> (await http.get<Brief>('/creation-defaults')).data,
  save:async(brief:Brief)=> (await http.put<Brief>('/creation-defaults',brief)).data,
  reset:async()=> (await http.delete<Brief>('/creation-defaults')).data,
  updateBrief:async(id:string,brief:Brief)=>(await http.put<Conversation>(`/conversations/${id}/brief`,brief)).data,
  clearExecutions:async(id:string)=>(await http.delete<Conversation>(`/conversations/${id}/execution-records`)).data,
};
