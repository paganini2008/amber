import type { En } from '../en'

export const findings: En['findings'] = {
  rules: {
    'AMB-S001': {
      title: 'Producto cartesiano',
      suggestion: 'Agregue condiciones de unión, o escriba CROSS JOIN si el producto es intencional.',
      description: 'Varias tablas en FROM sin ninguna condición de unión ni WHERE: cada fila se combina con todas las demás.'
    },
    'AMB-S002': {
      title: 'LEFT JOIN que actúa como INNER JOIN',
      suggestion: 'Mueva la condición a la cláusula ON, o use INNER JOIN si es lo que se pretende.',
      description: 'Una condición WHERE sobre la tabla derecha de un LEFT JOIN descarta las filas que la unión externa conservaba.'
    },
    'AMB-S003': {
      title: 'Comodín al inicio',
      suggestion: 'Fije el patrón al inicio, o use búsqueda de texto completo.',
      description: 'LIKE \'%x\' no puede usar un índice sobre la columna.'
    },
    'AMB-S004': {
      title: 'NOT IN con una subconsulta',
      suggestion: 'Use NOT EXISTS, o filtre los NULL en la subconsulta.',
      description: 'Un solo NULL de la subconsulta hace que NOT IN no coincida con ninguna fila (seguro cuando los metadatos indican que la columna admite NULL).'
    },
    'AMB-S005': {
      title: 'Tabla leída varias veces',
      suggestion: 'Léala una sola vez en una CTE (o con agregación condicional) y reutilice el resultado.',
      description: 'La misma tabla se lee varias veces en una sentencia.'
    },
    'AMB-S006': {
      title: 'DML fila por fila dentro de un bucle',
      suggestion: 'Reemplace el bucle por una sola sentencia basada en conjuntos (INSERT … SELECT, UPDATE … FROM, MERGE), o procese por lotes (FORALL / enlaces masivos).',
      description: 'Un INSERT, UPDATE, DELETE o MERGE se ejecuta una vez por cada iteración del bucle.'
    },
    'AMB-S007': {
      title: 'COMMIT dentro de un bucle',
      suggestion: 'Confirme una sola vez después del bucle, o en lotes más grandes.',
      description: 'Confirmar en cada iteración es lento y deja resultados parciales si el bucle falla.'
    },
    'AMB-S008': {
      title: 'Función sobre una columna filtrada',
      suggestion: 'Compare la columna sin transformar con un valor transformado (p. ej., un rango de fechas en lugar de TRUNC(col)).',
      description: 'Una función o un cálculo sobre una columna en WHERE impide usar su índice (alta cuando los metadatos muestran el índice).'
    },
    'AMB-S009': {
      title: 'UNION elimina duplicados',
      suggestion: 'Use UNION ALL cuando las ramas no puedan solaparse o los duplicados no importen.',
      description: 'UNION ordena o aplica hash al resultado; puede bastar con UNION ALL.'
    },
    'AMB-S010': {
      title: 'SELECT * escrito en una tabla o vista',
      suggestion: 'Enumere las columnas explícitamente.',
      description: 'La lista de columnas cambia cada vez que cambia la tabla de origen.'
    },
    'AMB-S011': {
      title: 'DISTINCT junto con GROUP BY',
      suggestion: 'Quite DISTINCT, salvo que la lista de selección agregue columnas fuera de los grupos.',
      description: 'GROUP BY ya devuelve una fila por grupo.'
    },
    'AMB-S012': {
      title: 'ORDER BY en una subconsulta',
      suggestion: 'Quite ORDER BY, o muévalo a la consulta más externa.',
      description: 'La consulta externa no conserva el orden de una subconsulta o CTE sin límite de filas.'
    },
    'AMB-M001': {
      title: 'Unión de muchos a muchos',
      suggestion: 'Una por una clave única, o agregue primero un lado a una fila por clave de unión. Compruebe si los totales están inflados.',
      description: 'Las columnas de unión no son únicas en ningún lado, así que las filas se repiten en ambos.'
    },
    'AMB-D001': {
      title: 'Sentencia duplicada',
      suggestion: 'Elimine la repetición o extráigala a un procedimiento.',
      description: 'La misma sentencia aparece de nuevo (sin tener en cuenta los literales).'
    },
    'AMB-D002': {
      title: 'Subconsulta repetida',
      suggestion: 'Calcúlela una vez en una CTE o tabla temporal y reutilícela.',
      description: 'La misma subconsulta aparece varias veces en el script.'
    },
    'AMB-I001': {
      title: 'SQL dinámico',
      suggestion: 'Siempre que sea posible, use SQL estático para que la sentencia pueda revisarse.',
      description: 'El SQL construido en tiempo de ejecución no se analiza.'
    },
    'AMB-I002': {
      title: 'Columnas sin tabla',
      suggestion: 'Califique las columnas con un alias de tabla, o agregue metadatos (importación de DDL, paquete de metadatos o modo en línea).',
      description: 'Columnas que no se pudieron atribuir a una tabla.'
    },
    'AMB-I003': {
      title: 'Comprobaciones detenidas antes de tiempo',
      suggestion: 'Divida el script en archivos más pequeños, o aumente AMBER_FINDINGS_SECONDS.',
      description: 'Las comprobaciones agotaron su tiempo disponible (AMBER_FINDINGS_SECONDS, 60 de forma predeterminada); las sentencias posteriores no se comprobaron.'
    },
    'AMB-P001': {
      title: 'Posible índice faltante',
      suggestion: 'Considere un índice que empiece por la columna. Esta pista se basa en el uso del script y la definición de la tabla; antes de agregarlo, revise en la base de datos los volúmenes reales y los índices existentes.',
      description: 'Una columna usada en uniones o filtros no tiene ningún índice que empiece por ella en la definición de tabla conocida (sube a baja cuando se usa en 3 o más sentencias). Es solo una pista: revise los volúmenes reales y los índices existentes.'
    },
    'AMB-P002': {
      title: 'Conversión implícita',
      suggestion: 'Compare con un valor del tipo de la columna (un número sin comillas, una cadena entre comillas, una fecha con formato explícito), o convierta el valor, nunca la columna.',
      description: 'Una columna de tipo conocido se compara con un valor de otro tipo, así que la base de datos convierte un lado: la columna (no se puede usar un índice) o el valor (con formatos que dependen de la sesión o errores en tiempo de ejecución).'
    },
    'AMB-P003': {
      title: 'Subconsulta correlacionada en la lista de selección',
      suggestion: 'Calcúlela una sola vez para todas las filas: una con una tabla derivada agrupada, o use APPLY / LATERAL. Amber ofrece una reescritura verificada para la forma de agregado simple.',
      description: 'Una subconsulta escalar que hace referencia a la fila externa se ejecuta una vez por fila; una unión con una tabla derivada agrupada se ejecuta una sola vez.'
    },
    'AMB-P004': {
      title: 'Sentencias que se pueden combinar',
      suggestion: 'Combínelas en una sola sentencia (un UPDATE, o un upsert como MERGE), que lee y escribe las filas una sola vez. Revise los disparadores que cuentan sentencias.',
      description: 'Varios UPDATE consecutivos de las mismas filas, o un UPDATE más un INSERT … WHERE NOT EXISTS sobre la misma tabla (un upsert), pueden ser una sola sentencia.'
    },
    'AMB-P005': {
      title: 'Bucle de cursor que puede ser una sola sentencia',
      suggestion: 'Reemplace el bucle por una sentencia basada en conjuntos (UPDATE … FROM / MERGE, INSERT … SELECT, DELETE … WHERE EXISTS). Compruebe antes que el cursor no pueda devolver dos veces la misma fila de destino y que no se necesite manejo de errores ni COMMIT por fila.',
      description: 'Un bucle de cursor cuyo cuerpo es una sola DML que solo usa la fila del cursor puede escribirse como una sentencia basada en conjuntos.'
    },
    'AMB-P006': {
      title: 'OR entre columnas indexadas',
      suggestion: 'Divida la consulta en ramas UNION ALL, una por condición indexada (excluyendo las filas de las ramas anteriores), o compruebe que el plan combina los índices.',
      description: 'Un OR entre condiciones sobre distintas columnas indexadas de una tabla puede impedir que se use cualquier índice individual.'
    },
    'AMB-P007': {
      title: 'Cálculo sobre una columna comparada',
      suggestion: 'Pase el cálculo al otro lado de la comparación para que la columna quede sin transformar (p. ej., col + 1 = 5 pasa a col = 5 - 1).',
      description: 'La aritmética o la concatenación sobre una columna en WHERE / ON, o NVL / COALESCE / ISNULL sobre una columna de unión, impide usar su índice.'
    },
    'AMB-P008': {
      title: 'COUNT usado para comprobar existencia',
      suggestion: 'Use EXISTS / NOT EXISTS (SELECT 1 FROM … WHERE …), que se detiene en la primera fila coincidente.',
      description: 'COUNT(*) > 0 cuenta todas las filas coincidentes; EXISTS se detiene en la primera.'
    },
    'AMB-P009': {
      title: 'DISTINCT que oculta una unión con duplicados',
      suggestion: 'Compruebe las otras tablas con EXISTS (SELECT 1 FROM … WHERE …) en lugar de unirlas, y quite DISTINCT: no se producen duplicados ni hace falta ordenar o aplicar hash.',
      description: 'Las columnas seleccionadas ya incluyen una clave única de una tabla; DISTINCT solo elimina los duplicados que producen las uniones.'
    }
  }
}
