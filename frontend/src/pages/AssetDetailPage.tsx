import { useParams } from 'react-router';

export default function AssetDetailPage() {
  const { id } = useParams<{ id: string }>();
  return <h1 className="text-2xl font-semibold p-4">자산 {id}</h1>;
}
