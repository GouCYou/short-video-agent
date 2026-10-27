import {http} from './api';
export interface ModelSettings {model:string;baseUrl:string;maskedKey:string;hasKey:boolean;revision:number;updatedAt:string|null;format:string}
export const modelSettingsApi={
 get:async()=>(await http.get<ModelSettings>('/admin/model-settings')).data,
 save:async(value:{model:string;baseUrl:string;apiKey?:string;revision:number})=>(await http.put<ModelSettings>('/admin/model-settings',value)).data,
};
